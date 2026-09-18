package com.fluxit.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxit.domain.auth.AuthError
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthSession
import com.fluxit.domain.auth.AuthUser
import com.fluxit.domain.auth.SessionTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

private fun SessionGateState.traceName(): String = when (this) {
    SessionGateState.Resolving -> "Resolving"
    SessionGateState.SignedOut -> "SignedOut"
    is SessionGateState.Ready -> "Ready"
    is SessionGateState.ResolutionFailed -> "ResolutionFailed(${error::class.simpleName})"
}

private fun AuthSession.traceName(): String = when (this) {
    AuthSession.Unresolved -> "Unresolved"
    AuthSession.SignedOut -> "SignedOut"
    is AuthSession.Authenticated -> "Authenticated"
    is AuthSession.ResolutionFailed -> "ResolutionFailed(${error::class.simpleName})"
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
 */
class SessionGateViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _gate = MutableStateFlow<SessionGateState>(SessionGateState.Resolving)
    val gate: StateFlow<SessionGateState> = _gate.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    /** True while an explicit recovery action (retry / sign out and retry) is running. */
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    /**
     * The newest value seen on [AuthRepository.session], whether or not it has been
     * published to [gate] yet. Only touched from [viewModelScope], which is confined to
     * the main dispatcher, so it needs no synchronisation.
     */
    private var latestSession: AuthSession = AuthSession.Unresolved

    /** False until the initial [AuthRepository.restoreSession] call has returned. */
    private var initialRestorationComplete: Boolean = false

    init {
        SessionTrace.event("gate created; state=Resolving; userScopedWorkAllowed=false")
        viewModelScope.launch {
            authRepository.session.collect { session ->
                latestSession = session
                publishGateState(source = "session=${session.traceName()}")
            }
        }
        viewModelScope.launch {
            SessionTrace.event("restoreSession() requested")
            authRepository.restoreSession()
            // Both adapters publish restoration's outcome onto `session` and then
            // return, so the emission is already queued but has not been delivered to
            // the collector above yet. Draining the dispatcher queue first means the
            // publish below uses restoration's own result rather than the stale cached
            // value it just invalidated. This narrows, but does not provably close, that
            // window: FB-101's `restoreSession()` returns Unit, so its outcome is only
            // observable through the flow, and a delivery that hops threads could still
            // land afterwards - in which case the next emission corrects the gate.
            yield()
            initialRestorationComplete = true
            publishGateState(source = "restoreSession() returned")
        }
    }

    /**
     * Recomputes [gate] from [latestSession], holding it at [SessionGateState.Resolving]
     * until the initial restoration has returned (see the class KDoc).
     */
    private fun publishGateState(source: String) {
        val next = if (initialRestorationComplete) {
            latestSession.toGateState()
        } else {
            SessionGateState.Resolving
        }
        SessionTrace.event(
            "$source -> gate=${next.traceName()}; " +
                "restorationComplete=$initialRestorationComplete; " +
                "userScopedWorkAllowed=${next.allowsUserScopedWork}",
        )
        _gate.value = next
    }

    /**
     * Retries session resolution. Safe per the FB-101 contract, and sufficient for a
     * transient failure (for example [AuthError.NetworkUnavailable]).
     */
    fun retryResolution() {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            SessionTrace.event("recovery: retryResolution()")
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
            SessionTrace.event("recovery: signOutAndRetry() - signing out")
            authRepository.signOut()
            authRepository.restoreSession()
            SessionTrace.event("recovery: signOutAndRetry() - complete")
            _isBusy.value = false
        }
    }

    /** Signs the current user out from inside the authenticated area. */
    fun signOut() {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            SessionTrace.event("signOut() requested from authenticated area")
            authRepository.signOut()
            _isBusy.value = false
        }
    }
}
