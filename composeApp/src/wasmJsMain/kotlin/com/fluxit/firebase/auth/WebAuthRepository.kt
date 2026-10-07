package com.fluxit.firebase.auth

import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthResult
import com.fluxit.domain.auth.AuthSession
import com.fluxit.domain.auth.AuthUser
import com.fluxit.firebase.WebBridgeError
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Web [AuthRepository] backed by the Firebase JS SDK, ported from `IosAuthRepository`
 * (decision D2: copy the iOS adapter first). Same state machine and error handling; the
 * Swift bridge is replaced by [WebAuthBridge] over `firebase-bridge.mjs`.
 *
 * One web-specific step: the JS SDK loads the persisted credential asynchronously, so
 * [restoreSession] waits for [WebAuthBridge.awaitPersistence] before reading
 * `currentUser`, or a signed-in reload would resolve as signed out.
 *
 * [session] starts at [AuthSession.Unresolved]. Production wraps this in
 * `SessionAuthRepository`, which publishes only what [restoreSession] or an interactive
 * operation resolved, never a raw listener state.
 */
internal class WebAuthRepository(
    private val bridgeProvider: () -> WebAuthBridge,
    private val diagnostics: AuthDiagnostics,
) : AuthRepository {

    /** Production constructor: the bridge over the Firebase JS SDK, started on first use. */
    constructor() : this({ JsWebAuthBridge }, ConsoleAuthDiagnostics)

    private val state = MutableStateFlow<AuthSession>(AuthSession.Unresolved)

    /**
     * Emits the currently known session, then every later change, while keeping a
     * Firebase auth-state listener registered exactly for the lifetime of the collection.
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
     * Resolves the persisted credential and publishes the outcome. A persisted user is
     * re-read from the server so a revoked, disabled or deleted account does not pass as
     * a live session; a failure publishes [AuthSession.ResolutionFailed] and keeps the
     * credential, so calling this again is a meaningful retry.
     */
    override suspend fun restoreSession() {
        val bridge = bridgeProvider()
        suspendCancellableCoroutine { continuation ->
            bridge.awaitPersistence {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
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
        block: (WebAuthBridge, (WebBridgeError?) -> Unit) -> Unit,
    ): AuthResult {
        val bridge = bridgeProvider()
        val error = awaitCompletion { completion -> block(bridge, completion) }
        return if (error == null) {
            state.value = sessionFor(bridge.currentUser())
            AuthResult.Success
        } else {
            // A wrong password is an operation failure, not a broken session.
            AuthResult.Failure(mapAuthFailure(operation, error, diagnostics))
        }
    }

    private fun sessionFor(user: AuthUser?): AuthSession =
        if (user == null) AuthSession.SignedOut else AuthSession.Authenticated(user)
}

/** Turns a bridge completion handler into a cancellable `suspend` call; tolerates double calls. */
private suspend fun awaitCompletion(block: ((WebBridgeError?) -> Unit) -> Unit): WebBridgeError? =
    suspendCancellableCoroutine { continuation ->
        block { error ->
            if (continuation.isActive) {
                continuation.resume(error)
            }
        }
    }
