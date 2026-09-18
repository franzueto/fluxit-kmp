package com.fluxit.firebase.auth

import android.util.Log
import com.fluxit.domain.auth.AuthError
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import java.io.IOException

/**
 * Platform-side diagnostics sink for Firebase Auth failures.
 *
 * FB-101-NB2: `AuthError.Unknown` deliberately carries no payload, so the original SDK
 * error code/exception is invisible from `commonMain`. It must therefore be logged
 * *here*, before the failure is collapsed into the shared taxonomy, or the detail is
 * lost with no way to recover it.
 */
internal interface AuthDiagnostics {

    /**
     * Called for every SDK failure, before the mapped [AuthError] is returned.
     *
     * @param mapped the taxonomy case the failure was translated into.
     * @param recognised false when nothing matched and [mapped] is [AuthError.Unknown];
     *   those are logged at a higher level because they are the ones that lose detail.
     */
    fun onSdkFailure(
        operation: String,
        throwable: Throwable,
        errorCode: String?,
        mapped: AuthError,
        recognised: Boolean,
    )
}

/** Default [AuthDiagnostics]: logcat only, never a crash and never a user-visible string. */
internal object LogcatAuthDiagnostics : AuthDiagnostics {

    const val TAG: String = "FluxItAuth"

    override fun onSdkFailure(
        operation: String,
        throwable: Throwable,
        errorCode: String?,
        mapped: AuthError,
        recognised: Boolean,
    ) {
        val message = "$operation failed: code=${errorCode ?: "none"} mapped=$mapped"
        if (recognised) {
            Log.w(TAG, message, throwable)
        } else {
            Log.e(TAG, "$message (UNMAPPED - original SDK error preserved only here)", throwable)
        }
    }
}

/**
 * Translates a Firebase Auth error code into the shared taxonomy, or `null` when the
 * code is not one FluxIt models.
 *
 * Kept as a pure `String` -> [AuthError] function so the whole mapping table is
 * unit-testable without constructing SDK exceptions.
 */
internal fun authErrorForCode(errorCode: String): AuthError? = when (errorCode) {
    "ERROR_INVALID_EMAIL" -> AuthError.InvalidEmail
    "ERROR_WRONG_PASSWORD",
    "ERROR_INVALID_CREDENTIAL",
    "ERROR_INVALID_LOGIN_CREDENTIALS",
    -> AuthError.InvalidCredentials
    "ERROR_EMAIL_ALREADY_IN_USE",
    "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL",
    "ERROR_CREDENTIAL_ALREADY_IN_USE",
    -> AuthError.EmailAlreadyInUse
    "ERROR_WEAK_PASSWORD" -> AuthError.WeakPassword
    "ERROR_USER_NOT_FOUND" -> AuthError.UserNotFound
    "ERROR_USER_DISABLED" -> AuthError.UserDisabled
    "ERROR_TOO_MANY_REQUESTS" -> AuthError.TooManyRequests
    "ERROR_NETWORK_REQUEST_FAILED" -> AuthError.NetworkUnavailable
    "ERROR_USER_TOKEN_EXPIRED",
    "ERROR_INVALID_USER_TOKEN",
    "ERROR_SESSION_EXPIRED",
    "ERROR_REQUIRES_RECENT_LOGIN",
    -> AuthError.SessionExpired
    else -> null
}

/**
 * Maps [throwable] to the shared taxonomy after reporting it to [diagnostics].
 *
 * Nothing about the original exception - its type, code or message - escapes this
 * function: callers only ever see an [AuthError].
 */
internal fun mapAuthFailure(
    operation: String,
    throwable: Throwable,
    diagnostics: AuthDiagnostics,
): AuthError {
    val errorCode = (throwable as? FirebaseAuthException)?.errorCode
    val mapped = errorCode?.let(::authErrorForCode) ?: mapByType(throwable)
    diagnostics.onSdkFailure(
        operation = operation,
        throwable = throwable,
        errorCode = errorCode,
        mapped = mapped ?: AuthError.Unknown,
        recognised = mapped != null,
    )
    return mapped ?: AuthError.Unknown
}

/**
 * Fallback for failures that carry no Firebase Auth error code, e.g. a transport-level
 * exception thrown before the backend ever answered.
 */
private fun mapByType(throwable: Throwable): AuthError? = when (throwable) {
    is FirebaseNetworkException, is IOException -> AuthError.NetworkUnavailable
    is FirebaseTooManyRequestsException -> AuthError.TooManyRequests
    else -> null
}
