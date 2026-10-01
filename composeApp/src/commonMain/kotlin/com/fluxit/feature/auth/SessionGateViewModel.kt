package com.fluxit.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxit.domain.auth.AuthError
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthSession
import com.fluxit.domain.auth.AuthUser
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * What the application root is allowed to show, derived from [AuthSession].
 *
 * The whole point of this type is that [Resolving] and [SignedOut] are *different*
 * states, mirroring `AuthSession.Unresolved` vs `AuthSession.SignedOut` (FB-101). Only
 * [Ready] permits user-scoped work; every other state must render a screen that starts
 * no listener and reads no uid.
 */
sealed interface SessionGateState {

    /** Session resolution is still in flight. No user-scoped work may start. */
    data object Resolving : SessionGateState

    /** Resolved: nobody is signed in. Show the authentication UI. */
    data object SignedOut : SessionGateState

    /** Resolved: [user] is signed in; `user.uid` may now scope listeners and paths. */
    data class Ready(val user: AuthUser) : SessionGateState

    /**
     * Session resolution itself failed. Retrying is meaningful, but per FB-102-NB2 and
     * its iOS mirror a hard failure (notably [AuthError.SessionExpired]) has **no
     * automatic path back to signed-out**, so the UI must also offer an explicit
     * sign-out-and-retry escape hatch.
     */
    data class ResolutionFailed(val error: AuthError) : SessionGateState
}

/** True only when the session is resolved to a real user. */
val SessionGateState.allowsUserScopedWork: Boolean
    get() = this is SessionGateState.Ready

/** Pure mapping from the domain session to the gate state, so it can be unit-tested. */
fun AuthSession.toGateState(): SessionGateState = when (this) {
    AuthSession.Unresolved -> SessionGateState.Resolving
    AuthSession.SignedOut -> SessionGateState.SignedOut
    is AuthSession.Authenticated -> SessionGateState.Ready(user)
    is AuthSession.ResolutionFailed -> SessionGateState.ResolutionFailed(error)
}

/**
 * The application root session gate (FB-104).
 *
 * It owns exactly one responsibility: turn [AuthRepository.session] into a
 * [SessionGateState] and kick off session restoration once. It never touches
 * user-scoped data itself, and the composable that renders it must not compose any
 * user-scoped screen unless the state is [SessionGateState.Ready].
 *
 * ## Why the initial restoration gates everything (FB-104-B1)
 *
 * Both platform adapters register an SDK auth-state listener when [AuthRepository.session]
 * is first collected, and that listener fires straight away with whatever credential is
 * cached locally. That emission races [AuthRepository.restoreSession], whose entire job is
 * to validate the cached credential against the server. Reflecting the raw flow would
 * therefore publish [SessionGateState.Ready] - unlocking user-scoped work - on the strength
 * of an unvalidated cached credential, and only later revert to
 * [SessionGateState.ResolutionFailed] when the server rejected it.
 *
 * So the gate suppresses every session emission until the initial [AuthRepository.restoreSession]
 * has *returned*, staying [SessionGateState.Resolving] for that whole window, and mirrors the
 * session flow normally from then on. "Resolved" means resolution actually finished, not that
 * a cached value was available. This sequencing lives here rather than in either adapter: the
 * adapters are correct as written (FB-101's contract makes `session` the live truth and
 * `restoreSession` the resolver), and FB-102/FB-103 stay untouched.
 *
 * Restoration is started from `init` rather than from a composable side effect so that
 * it happens exactly once per gate lifetime and is independent of composition on either
 * platform. On iOS this runs after Koin's existing start point
 * (`MainViewController.kt`), which is itself after `FirebaseApp.configure()` and Auth
 * bridge registration in `AppDelegate` - the ordering FB-103 established, unchanged.
 *
 * ## Why that window is bounded (FB-104-NB2, settled by DEC-006)
 *
 * Blocking on restoration is what makes `Ready` trustworthy, but it also means a
 * restoration that never completes would leave the app on the spinner forever. Per
 * DEC-006 the wait is bounded by [InitialRestorationTimeout]; on expiry the gate falls
 * back to [SessionGateState.SignedOut] and [restoreTimedOut] turns true so the auth
 * screen can explain why and offer [retryInitialRestoration]. The fallback is signed-out
 * rather than an error screen deliberately: it is a resolved state that shows no user
 * data and needs no validated uid. Note it is a fallback even when a *cached* credential
 * was already reported - timing out is not permission to trust an unvalidated one.
 */
class SessionGateViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _gate = MutableStateFlow<SessionGateState>(SessionGateState.Resolving)
    val gate: StateFlow<SessionGateState> = _gate.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    /** True while an explicit recovery action (retry / sign out and retry) is running. */
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _restoreTimedOut = MutableStateFlow(false)

    /**
     * True when the initial restoration exceeded [InitialRestorationTimeout] and the gate
     * fell back to [SessionGateState.SignedOut] (DEC-006). The auth screen uses it to show
     * a non-alarming notice and a retry affordance; it clears itself as soon as the
     * session resolves for real, whether by [retryInitialRestoration] or by a late
     * emission from the restoration that was still in flight.
     */
    val restoreTimedOut: StateFlow<Boolean> = _restoreTimedOut.asStateFlow()

    /**
     * The newest value seen on [AuthRepository.session], whether or not it has been
     * published to [gate] yet. Only touched from [viewModelScope], which is confined to
     * the main dispatcher, so it needs no synchronisation; it is a flow rather than a
     * plain field only so restoration can *await* a resolved value (see
     * [awaitInitialResolution]).
     */
    private val latestSession = MutableStateFlow<AuthSession>(AuthSession.Unresolved)

    /** False until the initial [AuthRepository.restoreSession] call has returned. */
    private var initialRestorationComplete: Boolean = false

    private var restorationJob: Job? = null

    init {
        viewModelScope.launch {
            // Cancellation still releases the SDK listener through the adapter's awaitClose.
            authRepository.session.collect { session ->
                latestSession.value = session
                // A later resolved value supersedes any restoration timeout fallback.
                if (session != AuthSession.Unresolved) _restoreTimedOut.value = false
                publishGateState()
            }
        }
        startInitialRestoration()
    }

    /**
     * Runs the initial restoration under the DEC-006 budget and publishes its outcome.
     *
     * Replaces any restoration already in flight, so a retry after a timeout cannot race
     * the attempt it is replacing.
     */
    private fun startInitialRestoration() {
        restorationJob?.cancel()
        restorationJob = viewModelScope.launch {
            initialRestorationComplete = false
            _restoreTimedOut.value = false
            publishGateState()

            val resolved = withTimeoutOrNull(InitialRestorationTimeout) {
                authRepository.restoreSession()
                awaitInitialResolution()
                true
            }

            initialRestorationComplete = true
            if (resolved == null) {
                _restoreTimedOut.value = true
                publishGateState()
            } else {
                publishGateState()
            }
        }
    }

    /**
     * Waits for restoration's outcome to be observable on [latestSession].
     *
     * FB-104-NB1, narrowed but explicitly **not closed**. `restoreSession()` returns
     * `Unit`, so its outcome is only observable through the session flow. Two separate
     * gaps follow, and they need different treatment:
     *
     * - *The emission is queued but not yet delivered.* [yield] drains the dispatcher
     *   queue, which is exactly right for both real adapters (they publish synchronously
     *   on the calling coroutine). A delivery that hops dispatchers could still land
     *   after the yield; the next emission then corrects the gate, and the gate never
     *   granted `Ready` on an unvalidated credential in the meantime.
     * - *Nothing has resolved at all yet.* Here waiting is unambiguously correct, so the
     *   gate waits, bounded by the same DEC-006 budget as the call itself.
     *
     * What deliberately is **not** done is waiting for a *new* emission after
     * restoration returns. That looks stronger and is in fact wrong: when restoration
     * confirms an already-cached credential, the adapters' `distinctUntilChanged` flow
     * emits nothing at all, so such a wait would stall every ordinary cold start until
     * the timeout. Closing the gap properly needs `restoreSession()` to return its own
     * outcome - an FB-101 contract change, out of scope here (see the FB-105 report).
     */
    private suspend fun awaitInitialResolution() {
        yield()
        if (latestSession.value == AuthSession.Unresolved) {
            latestSession.first { it != AuthSession.Unresolved }
        }
    }

    /**
     * Recomputes [gate] from [latestSession], holding it at [SessionGateState.Resolving]
     * until the initial restoration has returned (see the class KDoc).
     */
    private fun publishGateState() {
        val next = when {
            !initialRestorationComplete -> SessionGateState.Resolving
            // DEC-006: a timed-out restoration resolves to signed-out regardless of what
            // the underlying flow last said, because whatever it said was never validated.
            _restoreTimedOut.value -> SessionGateState.SignedOut
            else -> latestSession.value.toGateState()
        }
        _gate.value = next
    }

    /**
     * Retry offered on the signed-out screen after [restoreTimedOut] (DEC-006).
     *
     * Distinct from [retryResolution]: that one retries a *resolved* failure without
     * reopening the gate, whereas this restarts the whole initial-restoration sequence -
     * back to [SessionGateState.Resolving], under a fresh timeout budget - because the
     * previous attempt never produced an outcome at all.
     */
    fun retryInitialRestoration() {
        if (restorationJob?.isActive == true) return
        startInitialRestoration()
    }

    /**
     * Retries session resolution. Safe per the FB-101 contract, and sufficient for a
     * transient failure (for example [AuthError.NetworkUnavailable]).
     */
    fun retryResolution() {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            authRepository.restoreSession()
            _isBusy.value = false
        }
    }

    /**
     * Explicit escape hatch required by FB-102-NB2 and its iOS mirror: neither adapter
     * signs out on a hard resolution failure, so a revoked or expired credential can
     * leave the gate in [SessionGateState.ResolutionFailed] across any number of bare
     * retries. Signing out first clears that credential and gives the user a path back
     * to [SessionGateState.SignedOut] and the sign-in form.
     */
    fun signOutAndRetry() {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            authRepository.signOut()
            authRepository.restoreSession()
            _isBusy.value = false
        }
    }

    /** Signs the current user out from inside the authenticated area. */
    fun signOut() {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            authRepository.signOut()
            _isBusy.value = false
        }
    }

    companion object {

        /**
         * How long the gate waits for the initial session restoration before falling
         * back to the signed-out screen (DEC-006).
         *
         * A product constant, not a correctness threshold: it may be retuned without
         * reopening DEC-006, and nothing below it depends on the exact value. It is
         * named and public so tests drive the same number the app ships.
         */
        val InitialRestorationTimeout: Duration = 10.seconds
    }
}
