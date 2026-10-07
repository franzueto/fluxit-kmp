package com.fluxit.domain.auth

import kotlinx.coroutines.flow.Flow

/**
 * Domain-facing authentication/session boundary.
 *
 * Contract notes that implementations must honour:
 *
 * - [session] is the single source of truth for the current identity. It emits
 *   [AuthSession.Unresolved] first and must emit a resolved value only once the
 *   underlying provider has actually reported a state. Collection must be
 *   cancellable: cancelling the collector releases any underlying listener.
 * - Interactive operations report failures through [AuthResult] and do not move the
 *   session into [AuthSession.ResolutionFailed]; a wrong password leaves the user
 *   signed out, it does not break session resolution.
 * - Only email + password is modelled. There is no provider argument and no
 *   account linking, but nothing here forecloses adding a provider later: that would be
 *   an additive change to this interface.
 * - Every operation is a `suspend` function over immutable, platform-neutral values, so
 *   an implementation may complete it from another runtime (for example an iOS `actual`
 *   that delegates to Swift and resumes on a completion handler, because Firebase code on iOS lives in Swift).
 */
interface AuthRepository {

    /**
     * The current session, starting from [AuthSession.Unresolved].
     *
     * Consumers (notably the application root gate) must treat [AuthSession.Unresolved]
     * as "do not start user-scoped work yet", never as "signed out".
     */
    val session: Flow<AuthSession>

    /**
     * Resolves any persisted session and publishes the outcome on [session]:
     * [AuthSession.Authenticated], [AuthSession.SignedOut], or
     * [AuthSession.ResolutionFailed] if resolution could not be completed.
     *
     * Safe to call again after a failure to retry resolution.
     */
    suspend fun restoreSession()

    /** Creates an email/password account and, on success, signs that user in. */
    suspend fun signUp(email: String, password: String): AuthResult

    /** Signs in with an existing email/password account. */
    suspend fun signIn(email: String, password: String): AuthResult

    /**
     * Triggers the provider's password-recovery email (the provider's built-in password
     * reset). Does not change the session.
     */
    suspend fun sendPasswordResetEmail(email: String): AuthResult

    /**
     * Signs the current user out and publishes [AuthSession.SignedOut].
     *
     * This is a suspending operation because implementations are additionally
     * responsible for tearing down user-scoped state and clearing the backend's local
     * persistent cache; callers must not assume it is instantaneous, and
     * must not assume any cached user data survives it.
     */
    suspend fun signOut(): AuthResult
}
