package com.fluxit

import com.fluxit.domain.auth.AuthError
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthResult
import com.fluxit.domain.auth.AuthSession
import com.fluxit.domain.auth.AuthUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [AuthRepository] for shared tests and future previews.
 *
 * It models the email+password rules the real providers enforce (DEC-001) closely
 * enough to exercise every session transition, and it deliberately contains no
 * backend-specific behaviour.
 */
class FakeAuthRepository(
    /** Accounts that already exist, as email to password. */
    initialAccounts: Map<String, String> = emptyMap(),
    /** Email of the account whose session is already persisted on this device, if any. */
    private val persistedAccountEmail: String? = null,
) : AuthRepository {

    private val accounts: MutableMap<String, String> = initialAccounts.toMutableMap()
    private val uids: MutableMap<String, String> = mutableMapOf()
    private var uidCounter = 0

    /** Injected failure for the next interactive operation; consumed on use. */
    var nextOperationFailure: AuthError? = null

    /** Injected failure for the next [restoreSession] call; consumed on use. */
    var nextRestoreFailure: AuthError? = null

    /** Emails for which a recovery mail was requested, in order. */
    val passwordResetsSent: MutableList<String> = mutableListOf()

    /** Number of completed [signOut] calls, used to assert teardown happened once. */
    var signOutCount: Int = 0
        private set

    private val _session = MutableStateFlow<AuthSession>(AuthSession.Unresolved)
    override val session: Flow<AuthSession> = _session.asStateFlow()

    /** Current value, for assertions that do not need to observe the stream. */
    val currentSession: AuthSession get() = _session.value

    init {
        initialAccounts.keys.forEach { uidFor(it) }
    }

    private fun uidFor(email: String): String = uids.getOrPut(email) { "uid-${uidCounter++}" }

    override suspend fun restoreSession() {
        nextRestoreFailure?.let { failure ->
            nextRestoreFailure = null
            _session.value = AuthSession.ResolutionFailed(failure)
            return
        }
        val email = persistedAccountEmail?.takeIf { accounts.containsKey(it) }
        _session.value = if (email == null) {
            AuthSession.SignedOut
        } else {
            AuthSession.Authenticated(AuthUser(uidFor(email), email, isEmailVerified = false))
        }
    }

    override suspend fun signUp(email: String, password: String): AuthResult {
        injectedFailure()?.let { return it }
        if (!email.looksLikeEmail()) return AuthResult.Failure(AuthError.InvalidEmail)
        if (accounts.containsKey(email)) return AuthResult.Failure(AuthError.EmailAlreadyInUse)
        if (password.length < MIN_PASSWORD_LENGTH) return AuthResult.Failure(AuthError.WeakPassword)
        accounts[email] = password
        _session.value = AuthSession.Authenticated(AuthUser(uidFor(email), email))
        return AuthResult.Success
    }

    override suspend fun signIn(email: String, password: String): AuthResult {
        injectedFailure()?.let { return it }
        if (!email.looksLikeEmail()) return AuthResult.Failure(AuthError.InvalidEmail)
        val stored = accounts[email] ?: return AuthResult.Failure(AuthError.UserNotFound)
        if (stored != password) return AuthResult.Failure(AuthError.InvalidCredentials)
        _session.value = AuthSession.Authenticated(AuthUser(uidFor(email), email))
        return AuthResult.Success
    }

    override suspend fun sendPasswordResetEmail(email: String): AuthResult {
        injectedFailure()?.let { return it }
        if (!email.looksLikeEmail()) return AuthResult.Failure(AuthError.InvalidEmail)
        if (!accounts.containsKey(email)) return AuthResult.Failure(AuthError.UserNotFound)
        passwordResetsSent += email
        return AuthResult.Success
    }

    override suspend fun signOut(): AuthResult {
        injectedFailure()?.let { return it }
        signOutCount++
        _session.value = AuthSession.SignedOut
        return AuthResult.Success
    }

    private fun injectedFailure(): AuthResult.Failure? =
        nextOperationFailure?.let {
            nextOperationFailure = null
            AuthResult.Failure(it)
        }

    private fun String.looksLikeEmail(): Boolean =
        contains('@') && substringAfter('@').contains('.') && !startsWith('@')

    private companion object {
        const val MIN_PASSWORD_LENGTH = 6
    }
}
