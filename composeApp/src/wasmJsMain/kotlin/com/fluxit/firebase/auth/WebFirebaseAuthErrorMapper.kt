package com.fluxit.firebase.auth

import com.fluxit.domain.auth.AuthError
import com.fluxit.firebase.WebBridgeError

/**
 * Platform-side diagnostics sink for Firebase Auth failures on web. Mirrors
 * `AuthDiagnostics` in `iosMain`: `AuthError.Unknown` carries no payload, so the SDK's
 * code and message are logged here before they are collapsed.
 */
internal interface AuthDiagnostics {

    /**
     * Called for every SDK failure, before the mapped [AuthError] is returned.
     *
     * @param recognised false when nothing matched and [mapped] is [AuthError.Unknown].
     */
    fun onSdkFailure(
        operation: String,
        error: WebBridgeError,
        mapped: AuthError,
        recognised: Boolean,
    )
}

/** Default [AuthDiagnostics]: the browser console only, never a user-visible string. */
internal object ConsoleAuthDiagnostics : AuthDiagnostics {

    const val TAG: String = "FluxItAuth"

    override fun onSdkFailure(
        operation: String,
        error: WebBridgeError,
        mapped: AuthError,
        recognised: Boolean,
    ) {
        val detail = "$TAG $operation failed: code=${error.code} mapped=$mapped message=${error.message}"
        if (recognised) consoleWarn(detail) else consoleError("$detail (UNMAPPED - original SDK error preserved only here)")
    }
}

private fun consoleWarn(message: String): Unit = js("console.warn(message)")

private fun consoleError(message: String): Unit = js("console.error(message)")

/**
 * Translates a Firebase JS SDK auth error code into the shared taxonomy, or `null` when
 * the code is not one FluxIt models. Follows the iOS table (`authErrorForFirebaseCode(Long)`)
 * for every email/password case, spelled as the JS SDK's `auth/...` strings. Differences:
 * iOS's phone-auth `sessionExpired` has no web counterpart; `auth/timeout` stands in for
 * iOS's `NSURLErrorDomain` timeout, since the JS SDK reports transport failures as auth
 * codes; `auth/invalid-login-credentials` is kept for older SDKs (12.x reports
 * `auth/invalid-credential`).
 */
internal fun authErrorForFirebaseCode(code: String): AuthError? = when (code) {
    "auth/invalid-email" -> AuthError.InvalidEmail
    "auth/invalid-credential",
    "auth/invalid-login-credentials",
    "auth/wrong-password",
    "auth/user-mismatch",
    -> AuthError.InvalidCredentials
    "auth/email-already-in-use",
    "auth/account-exists-with-different-credential",
    "auth/credential-already-in-use",
    -> AuthError.EmailAlreadyInUse
    "auth/weak-password" -> AuthError.WeakPassword
    "auth/user-not-found" -> AuthError.UserNotFound
    "auth/user-disabled" -> AuthError.UserDisabled
    "auth/too-many-requests" -> AuthError.TooManyRequests
    "auth/network-request-failed",
    "auth/timeout",
    -> AuthError.NetworkUnavailable
    "auth/requires-recent-login",
    "auth/invalid-user-token",
    "auth/user-token-expired",
    -> AuthError.SessionExpired
    else -> null
}

/**
 * Maps [error] to the shared taxonomy after reporting it to [diagnostics]. Callers only
 * ever see an [AuthError].
 */
internal fun mapAuthFailure(
    operation: String,
    error: WebBridgeError,
    diagnostics: AuthDiagnostics,
): AuthError {
    val mapped = authErrorForFirebaseCode(error.code)
    diagnostics.onSdkFailure(
        operation = operation,
        error = error,
        mapped = mapped ?: AuthError.Unknown,
        recognised = mapped != null,
    )
    return mapped ?: AuthError.Unknown
}
