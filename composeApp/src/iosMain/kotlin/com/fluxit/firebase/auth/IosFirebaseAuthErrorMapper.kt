package com.fluxit.firebase.auth

import com.fluxit.domain.auth.AuthError
import platform.Foundation.NSError
import platform.Foundation.NSLog
import platform.Foundation.NSURLErrorDomain

/**
 * Platform-side diagnostics sink for Firebase Auth failures on iOS.
 *
 * `AuthError.Unknown` deliberately carries no payload, so the original SDK
 * error domain/code/message is invisible from `commonMain`. It must therefore be logged
 * *here*, before the failure is collapsed into the shared taxonomy, or the detail is
 * lost with no way to recover it. Mirrors `AuthDiagnostics` in `androidMain`.
 */
internal interface AuthDiagnostics {

    /**
     * Called for every SDK failure, before the mapped [AuthError] is returned.
     *
     * @param recognised false when nothing matched and [mapped] is [AuthError.Unknown];
     *   those are logged more loudly because they are the ones that lose detail.
     */
    fun onSdkFailure(
        operation: String,
        error: NSError,
        mapped: AuthError,
        recognised: Boolean,
    )
}

/** Default [AuthDiagnostics]: `NSLog` only, never a crash and never a user-visible string. */
internal object NSLogAuthDiagnostics : AuthDiagnostics {

    const val TAG: String = "FluxItAuth"

    override fun onSdkFailure(
        operation: String,
        error: NSError,
        mapped: AuthError,
        recognised: Boolean,
    ) {
        val detail = "$operation failed: domain=${error.domain} code=${error.code} " +
            "mapped=$mapped description=${error.localizedDescription}"
        val line = if (recognised) {
            "$TAG W $detail"
        } else {
            "$TAG E $detail (UNMAPPED - original SDK error preserved only here)"
        }
        NSLog("%s", line)
    }
}

/** `FIRAuthErrorDomain`, spelled out so no Firebase symbol is referenced from Kotlin. */
internal const val FIREBASE_AUTH_ERROR_DOMAIN: String = "FIRAuthErrorDomain"

/**
 * Translates a `FIRAuthErrorDomain` code into the shared taxonomy, or `null` when the
 * code is not one FluxIt models.
 *
 * Values are the raw values of the Apple SDK's `AuthErrorCode`. They are written as
 * literals rather than referenced through the SDK because, per the Swift-only Firebase boundary on iOS, this file is
 * Kotlin and cannot see `FirebaseAuth`. Kept as a pure `Long` -> [AuthError] function so
 * the whole table is unit-testable without constructing SDK errors.
 */
internal fun authErrorForFirebaseCode(code: Long): AuthError? = when (code) {
    17008L -> AuthError.InvalidEmail // invalidEmail
    17004L, // invalidCredential (also carries INVALID_LOGIN_CREDENTIALS)
    17009L, // wrongPassword
    17024L, // userMismatch
    -> AuthError.InvalidCredentials
    17007L, // emailAlreadyInUse
    17012L, // accountExistsWithDifferentCredential
    17025L, // credentialAlreadyInUse
    -> AuthError.EmailAlreadyInUse
    17026L -> AuthError.WeakPassword // weakPassword
    17011L -> AuthError.UserNotFound // userNotFound
    17005L -> AuthError.UserDisabled // userDisabled
    17010L -> AuthError.TooManyRequests // tooManyRequests
    17020L -> AuthError.NetworkUnavailable // networkError
    17014L, // requiresRecentLogin
    17017L, // invalidUserToken
    17021L, // userTokenExpired
    17051L, // sessionExpired
    -> AuthError.SessionExpired
    else -> null
}

/**
 * Maps [error] to the shared taxonomy after reporting it to [diagnostics].
 *
 * Nothing about the original error - its domain, code or message - escapes this
 * function: callers only ever see an [AuthError].
 */
internal fun mapAuthFailure(
    operation: String,
    error: NSError,
    diagnostics: AuthDiagnostics,
): AuthError {
    val mapped = when (error.domain) {
        FIREBASE_AUTH_ERROR_DOMAIN -> authErrorForFirebaseCode(error.code)
        NSURLErrorDomain -> urlErrorToAuthError(error.code)
        else -> null
    }
    diagnostics.onSdkFailure(
        operation = operation,
        error = error,
        mapped = mapped ?: AuthError.Unknown,
        recognised = mapped != null,
    )
    return mapped ?: AuthError.Unknown
}

/**
 * Fallback for transport-level failures raised before the backend ever answered, which
 * surface as `NSURLErrorDomain` rather than as a Firebase Auth code. The Android
 * counterpart handles the same class of failure through `FirebaseNetworkException` /
 * `IOException`.
 */
private fun urlErrorToAuthError(code: Long): AuthError? = when (code) {
    -1001L, // timedOut
    -1003L, // cannotFindHost
    -1004L, // cannotConnectToHost
    -1005L, // networkConnectionLost
    -1009L, // notConnectedToInternet
    -1018L, // internationalRoamingOff
    -1020L, // dataNotAllowed
    -> AuthError.NetworkUnavailable
    else -> null
}
