package com.fluxit.firebase.auth

import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthResult
import com.fluxit.domain.auth.AuthSession
import com.fluxit.domain.auth.AuthUser
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError

/**
 * iOS [AuthRepository] backed by the official Firebase Apple Auth SDK (FB-103).
 *
 * Deliberately the same shape as `AndroidAuthRepository` (FB-102) so the two platforms
 * cannot drift, but not the same mechanism: per PLAN-008 the SDK itself is untouchable
 * from Kotlin here, so every Firebase call goes through the Swift-implemented
 * [IosAuthBridge].
 *
 * Design notes:
 *
 * - No Firebase type appears in any signature. The SDK is reached only through
 *   [IosAuthBridge]; failures arrive as a Foundation [NSError], are translated by
 *   [mapAuthFailure] and logged platform-side first (FB-101-NB2), so no SDK code,
 *   message or object ever crosses into `commonMain`.
 * - [session] is a `callbackFlow` whose `awaitClose` removes the Firebase auth-state
 *   listener through the bridge, so cancelling a collector genuinely releases the
 *   listener (FB-101-NB3).
 * - The published state lives in [state] rather than in the flow, so an operation
 *   performed while nobody is collecting still leaves the repository in the right
 *   state, and so [restoreSession] can publish [AuthSession.ResolutionFailed] - a state
 *   the SDK listener itself can never report.
 */
class IosAuthRepository internal constructor(
    private val bridgeProvider: () -> IosAuthBridge,
    private val diagnostics: AuthDiagnostics,
) : AuthRepository {

    /**
     * Production constructor: the bridge Swift registered in [IosAuthBridgeRegistry].
     *
     * Resolved lazily, per call, rather than at construction time, so a Koin `single`
     * may be created before the app layer has registered anything (and so a failure
     * names the real cause instead of surfacing as a null SDK object much later).
     */
    constructor() : this(IosAuthBridgeRegistry::requireBridge, NSLogAuthDiagnostics)

    private val state = MutableStateFlow<AuthSession>(AuthSession.Unresolved)

    /**
     * Emits the currently known session, then every later change, while keeping a
     * Firebase auth-state listener registered exactly for the lifetime of the
     * collection.
     *
     * The first emission is the current value of [state], which is
     * [AuthSession.Unresolved] until something has actually resolved it - a collector
     * can therefore never mistake "not known yet" for "signed out".
     */
    override val session: Flow<AuthSession> = callbackFlow {
        send(state.value)
        val bridge = bridgeProvider()
        val handle = bridge.addAuthStateListener { user ->
            state.value = sessionFor(user)
        }
        val relay = launch {
            state.collect { trySend(it) }
        }
        awaitClose {
            handle.remove()
            relay.cancel()
        }
    }.distinctUntilChanged()

    /**
     * Resolves the persisted credential and publishes the outcome.
     *
     * A locally persisted user is re-read from the server, so a credential that has been
     * revoked, disabled or deleted does not silently pass as a live session. A failure
     * publishes [AuthSession.ResolutionFailed] and leaves the persisted credential
     * alone, so calling this again later is a meaningful retry.
     *
     * Like FB-102, this deliberately does not sign out on a hard failure - see
     * FB-102-NB2, whose recovery-affordance consequence is owned by FB-104 and applies
     * identically here.
     */
    override suspend fun restoreSession() {
        val bridge = bridgeProvider()
        if (bridge.currentUser() == null) {
            state.value = AuthSession.SignedOut
            return
        }
        val error = awaitCompletion { completion -> bridge.reloadCurrentUser(completion) }
        if (error != null) {
            state.value = AuthSession.ResolutionFailed(
                mapAuthFailure("restoreSession", error, diagnostics),
            )
            return
        }
        state.value = sessionFor(bridge.currentUser())
    }

    override suspend fun signUp(email: String, password: String): AuthResult =
        runOperation("signUp") { bridge, completion -> bridge.signUp(email, password, completion) }

    override suspend fun signIn(email: String, password: String): AuthResult =
        runOperation("signIn") { bridge, completion -> bridge.signIn(email, password, completion) }

    /** Does not touch [session]: a recovery mail says nothing about who is signed in. */
    override suspend fun sendPasswordResetEmail(email: String): AuthResult {
        val bridge = bridgeProvider()
        val error = awaitCompletion { completion ->
            bridge.sendPasswordResetEmail(email, completion)
        }
        return if (error == null) {
            AuthResult.Success
        } else {
            AuthResult.Failure(mapAuthFailure("sendPasswordResetEmail", error, diagnostics))
        }
    }

    /** Auth credential removal. Production DI sequences this after SessionCleanup. */
    override suspend fun signOut(): AuthResult {
        val bridge = bridgeProvider()
        val error = awaitCompletion { completion -> bridge.signOut(completion) }
        return if (error == null) {
            state.value = AuthSession.SignedOut
            AuthResult.Success
        } else {
            AuthResult.Failure(mapAuthFailure("signOut", error, diagnostics))
        }
    }

    private suspend fun runOperation(
        operation: String,
        block: (IosAuthBridge, (NSError?) -> Unit) -> Unit,
    ): AuthResult {
        val bridge = bridgeProvider()
        val error = awaitCompletion { completion -> block(bridge, completion) }
        return if (error == null) {
            state.value = sessionFor(bridge.currentUser())
            AuthResult.Success
        } else {
            // The session is deliberately left untouched: a wrong password is an
            // operation failure, not a broken session.
            AuthResult.Failure(mapAuthFailure(operation, error, diagnostics))
        }
    }

    private fun sessionFor(user: AuthUser?): AuthSession =
        if (user == null) AuthSession.SignedOut else AuthSession.Authenticated(user)
}

/**
 * Turns one of [IosAuthBridge]'s completion handlers into a cancellable `suspend` call.
 *
 * Guards against a bridge that calls back twice (`isActive`), which a Swift
 * implementation could do by accident and which would otherwise crash the coroutine
 * machinery rather than the offending call site.
 */
private suspend fun awaitCompletion(block: ((NSError?) -> Unit) -> Unit): NSError? =
    suspendCancellableCoroutine { continuation ->
        block { error ->
            if (continuation.isActive) {
                continuation.resume(error)
            }
        }
    }
