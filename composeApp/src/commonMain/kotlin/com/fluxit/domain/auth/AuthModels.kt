package com.fluxit.domain.auth

/**
 * The signed-in identity, expressed without any backend-specific type.
 *
 * Only fields the app actually needs are modelled. `uid` is the single value every
 * user-scoped path and listener is keyed on.
 */
data class AuthUser(
    val uid: String,
    val email: String?,
    val isEmailVerified: Boolean = false,
)

/**
 * The resolved state of the authentication session.
 *
 * [Unresolved] and [SignedOut] are deliberately distinct types: "we do not know yet"
 * must never be confused with "we know there is no user". The application root gate
 * must not start any user-scoped listener while the session is [Unresolved].
 */
sealed interface AuthSession {

    /**
     * The session has not been resolved yet (app start, or a restoration in flight).
     * This is the initial value of [AuthRepository.session].
     */
    data object Unresolved : AuthSession

    /** Resolved: there is no signed-in user. */
    data object SignedOut : AuthSession

    /** Resolved: [user] is signed in and `user.uid` may be used to scope data access. */
    data class Authenticated(val user: AuthUser) : AuthSession

    /**
     * Session resolution itself failed (for example the network was unavailable while
     * restoring). This is *not* used for failures of an interactive operation such as a
     * wrong password; those are reported through [AuthResult] and leave the session
     * state unchanged.
     */
    data class ResolutionFailed(val error: AuthError) : AuthSession
}

/** True once the session is known, whether or not a user is signed in. */
val AuthSession.isResolved: Boolean
    get() = this !is AuthSession.Unresolved

/** The signed-in uid, or `null` in every other state (including [AuthSession.Unresolved]). */
val AuthSession.uidOrNull: String?
    get() = (this as? AuthSession.Authenticated)?.user?.uid

/**
 * Backend-neutral authentication error taxonomy.
 *
 * Platform adapters translate their SDK's error codes/exceptions into these cases and
 * must not surface raw codes, messages or exceptions across this boundary. [Unknown]
 * carries no payload on purpose: diagnostics are logged inside the adapter, they are
 * not part of the shared contract.
 */
sealed interface AuthError {
    /** Email/password combination rejected, or the supplied password is wrong. */
    data object InvalidCredentials : AuthError

    /** The email is not a syntactically/structurally acceptable address. */
    data object InvalidEmail : AuthError

    /** Sign-up rejected because an account already exists for that email. */
    data object EmailAlreadyInUse : AuthError

    /** Sign-up rejected because the password does not meet the backend's policy. */
    data object WeakPassword : AuthError

    /** No account exists for the supplied email. */
    data object UserNotFound : AuthError

    /** The account exists but has been disabled. */
    data object UserDisabled : AuthError

    /** Rate limited after too many attempts. */
    data object TooManyRequests : AuthError

    /** No usable network connection. Retrying later is meaningful. */
    data object NetworkUnavailable : AuthError

    /** The stored session is no longer valid and the user must sign in again. */
    data object SessionExpired : AuthError

    /** Anything the adapter could not map to a case above. */
    data object Unknown : AuthError
}

/**
 * Outcome of an interactive authentication operation.
 *
 * Intentionally non-generic: the signed-in identity is published by
 * [AuthRepository.session], which stays the single source of truth for who is signed
 * in. Keeping this free of generics also keeps the future iOS bridge simple.
 */
sealed interface AuthResult {
    data object Success : AuthResult
    data class Failure(val error: AuthError) : AuthResult
}

val AuthResult.isSuccess: Boolean
    get() = this is AuthResult.Success

val AuthResult.errorOrNull: AuthError?
    get() = (this as? AuthResult.Failure)?.error
