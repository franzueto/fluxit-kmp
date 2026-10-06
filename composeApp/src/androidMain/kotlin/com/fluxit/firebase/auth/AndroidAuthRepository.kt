package com.fluxit.firebase.auth

import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthResult
import com.fluxit.domain.auth.AuthSession
import com.fluxit.domain.auth.AuthUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Android [AuthRepository] backed by the official Firebase Android Auth SDK (FB-102).
 *
 * Design notes:
 *
 * - No Firebase type appears in any signature here. The SDK is reached only through
 *   [FirebaseAuthGateway]; failures are translated by [mapAuthFailure] and logged
 *   platform-side first (FB-101-NB2), so no SDK code, message or exception ever
 *   crosses into `commonMain`.
 * - [session] is a `callbackFlow` whose `awaitClose` removes the SDK auth-state
 *   listener, so cancelling a collector genuinely releases the listener (FB-101-NB3).
 * - The published state lives in [state] rather than in the flow, so an operation
 *   performed while nobody is collecting still leaves the repository in the right
 *   state, and so [restoreSession] can publish [AuthSession.ResolutionFailed] - a
 *   state the SDK listener itself can never report.
 */
class AndroidAuthRepository internal constructor(
    private val gateway: FirebaseAuthGateway,
    private val diagnostics: AuthDiagnostics,
) : AuthRepository {

    /** Production constructor: the default [com.google.firebase.auth.FirebaseAuth]. */
    constructor() : this(FirebaseSdkAuthGateway(), LogcatAuthDiagnostics)

    private val state = MutableStateFlow<AuthSession>(AuthSession.Unresolved)

    /**
     * Emits the currently known session, then every later change, while keeping an SDK
     * auth-state listener registered exactly for the lifetime of the collection.
     *
     * The first emission is the current value of [state], which is
     * [AuthSession.Unresolved] until something has actually resolved it - a collector
     * can therefore never mistake "not known yet" for "signed out". The SDK invokes a
     * freshly added listener once with the current state, so attaching a collector is
     * by itself enough to resolve the session; [restoreSession] additionally validates
     * the persisted credential against the server.
     */
    override val session: Flow<AuthSession> = callbackFlow {
        send(state.value)
        val registration = gateway.addAuthStateListener { user ->
            state.value = sessionFor(user)
        }
        val relay = launch {
            state.collect { trySend(it) }
        }
        awaitClose {
            registration.remove()
            relay.cancel()
        }
    }.distinctUntilChanged()

    /**
     * Resolves the persisted credential and publishes the outcome.
     *
     * A locally persisted user is re-read from the server, so a credential that has
     * been revoked, disabled or deleted does not silently pass as a live session. A
     * failure publishes [AuthSession.ResolutionFailed] and leaves the persisted
     * credential alone, so calling this again later is a meaningful retry.
     */
    override suspend fun restoreSession() {
        if (gateway.currentUser() == null) {
            state.value = AuthSession.SignedOut
            return
        }
        try {
            gateway.reloadCurrentUser()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            state.value = AuthSession.ResolutionFailed(
                mapAuthFailure("restoreSession", throwable, diagnostics),
            )
            return
        }
        state.value = sessionFor(gateway.currentUser())
    }

    override suspend fun signUp(email: String, password: String): AuthResult =
        runOperation("signUp") { gateway.signUp(email, password) }

    override suspend fun signIn(email: String, password: String): AuthResult =
        runOperation("signIn") { gateway.signIn(email, password) }

    /** Does not touch [session]: a recovery mail says nothing about who is signed in. */
    override suspend fun sendPasswordResetEmail(email: String): AuthResult {
        return try {
            gateway.sendPasswordResetEmail(email)
            AuthResult.Success
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            AuthResult.Failure(mapAuthFailure("sendPasswordResetEmail", throwable, diagnostics))
        }
    }

    /** Auth credential removal. Production DI sequences this after SessionCleanup. */
    override suspend fun signOut(): AuthResult {
        return try {
            gateway.signOut()
            state.value = AuthSession.SignedOut
            AuthResult.Success
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            AuthResult.Failure(mapAuthFailure("signOut", throwable, diagnostics))
        }
    }

    private suspend fun runOperation(operation: String, block: suspend () -> Unit): AuthResult {
        return try {
            block()
            state.value = sessionFor(gateway.currentUser())
            AuthResult.Success
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // The session is deliberately left untouched: a wrong password is an
            // operation failure, not a broken session.
            AuthResult.Failure(mapAuthFailure(operation, throwable, diagnostics))
        }
    }

    private fun sessionFor(user: AuthUser?): AuthSession =
        if (user == null) AuthSession.SignedOut else AuthSession.Authenticated(user)
}
