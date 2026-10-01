package com.fluxit.domain.session

import com.fluxit.domain.auth.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Serializes credential changes with local privacy cleanup. Failures keep all data work closed. */
class SessionAuthRepository(
    private val delegate: AuthRepository,
    private val work: SessionWork,
    private val cleanup: SessionCleanup,
) : AuthRepository {
    private val mutex = Mutex()
    private var blocked = cleanup.pending
    private var changing = false
    private var allowedUid: String? = null
    private val state = MutableStateFlow<AuthSession>(AuthSession.Unresolved)

    override val session = callbackFlow {
        val source = launch {
            delegate.session.collect { next ->
                if (!blocked && !changing) {
                    // Raw cached or delayed authentication is never permission to reopen.
                    if (next is AuthSession.Authenticated && next.user.uid == allowedUid) {
                        state.value = next
                    } else if (next == AuthSession.SignedOut && allowedUid != null) {
                        allowedUid = null
                        work.close()
                        state.value = next
                    }
                }
            }
        }
        val relay = launch { state.collect { send(it) } }
        awaitClose { source.cancel(); relay.cancel() }
    }.distinctUntilChanged()

    override suspend fun restoreSession(): Unit = mutex.withLock {
        if (blocked || cleanup.pending) {
            cleanSession()
            Unit
        } else {
            changing = true
            try {
                delegate.restoreSession()
                val resolved = delegate.session.first { it != AuthSession.Unresolved }
                publishResolved(resolved)
            } finally { changing = false }
        }
    }

    override suspend fun signIn(email: String, password: String) = changeCredential { delegate.signIn(email, password) }
    override suspend fun signUp(email: String, password: String) = changeCredential { delegate.signUp(email, password) }
    override suspend fun sendPasswordResetEmail(email: String) = delegate.sendPasswordResetEmail(email)
    override suspend fun signOut(): AuthResult = mutex.withLock { cleanSession() }

    private suspend fun publishResolved(resolved: AuthSession) {
        currentCoroutineContext().ensureActive()
        if (resolved is AuthSession.Authenticated) {
            work.open()
            allowedUid = resolved.user.uid
        } else {
            allowedUid = null
            work.close()
        }
        currentCoroutineContext().ensureActive()
        state.value = resolved
    }

    private suspend fun changeCredential(action: suspend () -> AuthResult): AuthResult = mutex.withLock {
        // Includes sign-in from the restoration-timeout screen with a cached credential.
        val result = cleanSession()
        if (result is AuthResult.Failure) return@withLock result
        changing = true
        try {
            cleanup.begin() // Interrupted credential changes must also clean up on restart.
            val auth = action()
            if (auth is AuthResult.Success) {
                val resolved = withTimeout(CleanupTimeoutMillis) {
                    delegate.session.first { it is AuthSession.Authenticated }
                }
                cleanup.complete()
                publishResolved(resolved)
            } else {
                cleanup.complete()
            }
            auth
        } catch (cancelled: CancellationException) {
            blockFailedSession()
            throw cancelled
        } catch (_: Exception) {
            blockFailedSession()
            AuthResult.Failure(AuthError.CleanupFailed)
        } finally { changing = false }
    }

    private suspend fun blockFailedSession() = withContext(NonCancellable) {
        blocked = true
        allowedUid = null
        // Cancellation may arrive after marker removal but before opening the gate.
        // Re-arm recovery; a failed write still leaves this process closed.
        runCatching { cleanup.begin() }
        work.close()
        state.value = AuthSession.ResolutionFailed(AuthError.CleanupFailed)
    }

    private suspend fun cleanSession(): AuthResult {
        blocked = true
        changing = true
        allowedUid = null
        state.value = AuthSession.Unresolved
        // Once requested, finish locally even if the initiating ViewModel is cleared.
        // The budget limits UI waiting; the durable marker forces retry on interruption.
        return withContext(NonCancellable) {
            try {
                withTimeout(CleanupTimeoutMillis) {
                    try { cleanup.begin() } finally { work.close() }
                    cleanup.clear()
                    if (delegate.signOut() is AuthResult.Failure) throw CleanupFailure()
                    cleanup.complete()
                }
                blocked = false
                state.value = AuthSession.SignedOut
                AuthResult.Success
            } catch (_: Exception) {
                state.value = AuthSession.ResolutionFailed(AuthError.CleanupFailed)
                AuthResult.Failure(AuthError.CleanupFailed)
            } finally { changing = false }
        }
    }

    private class CleanupFailure : Exception()
    companion object { const val CleanupTimeoutMillis = 15_000L }
}
