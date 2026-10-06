package com.fluxit.feature.auth

import fluxit.composeapp.generated.resources.session_cleanup_failed
import com.fluxit.domain.auth.AuthError
import fluxit.composeapp.generated.resources.Res
import fluxit.composeapp.generated.resources.auth_error_email_already_in_use
import fluxit.composeapp.generated.resources.auth_error_invalid_credentials
import fluxit.composeapp.generated.resources.auth_error_invalid_email
import fluxit.composeapp.generated.resources.auth_error_network_unavailable
import fluxit.composeapp.generated.resources.auth_error_session_expired
import fluxit.composeapp.generated.resources.auth_error_too_many_requests
import fluxit.composeapp.generated.resources.auth_error_unknown
import fluxit.composeapp.generated.resources.auth_error_user_disabled
import fluxit.composeapp.generated.resources.auth_error_user_not_found
import fluxit.composeapp.generated.resources.auth_error_weak_password
import fluxit.composeapp.generated.resources.auth_form_error_email_required
import fluxit.composeapp.generated.resources.auth_form_error_password_required
import fluxit.composeapp.generated.resources.auth_form_error_passwords_do_not_match
import org.jetbrains.compose.resources.StringResource

/**
 * Presentation-layer mapping from the neutral [AuthError] taxonomy to user-facing copy.
 *
 * Exhaustive by construction (no `else` branch), so adding a case to the taxonomy is a
 * compile error here rather than a silent "Something went wrong" in the UI.
 */
fun AuthError.messageResource(): StringResource = when (this) {
    AuthError.InvalidCredentials -> Res.string.auth_error_invalid_credentials
    AuthError.InvalidEmail -> Res.string.auth_error_invalid_email
    AuthError.EmailAlreadyInUse -> Res.string.auth_error_email_already_in_use
    AuthError.WeakPassword -> Res.string.auth_error_weak_password
    AuthError.UserNotFound -> Res.string.auth_error_user_not_found
    AuthError.UserDisabled -> Res.string.auth_error_user_disabled
    AuthError.TooManyRequests -> Res.string.auth_error_too_many_requests
    AuthError.NetworkUnavailable -> Res.string.auth_error_network_unavailable
    AuthError.SessionExpired -> Res.string.auth_error_session_expired
    AuthError.CleanupFailed -> Res.string.session_cleanup_failed
    AuthError.Unknown -> Res.string.auth_error_unknown
}

/** Local input problems, kept visually and semantically distinct from backend errors. */
fun AuthFormError.messageResource(): StringResource = when (this) {
    AuthFormError.EmailRequired -> Res.string.auth_form_error_email_required
    AuthFormError.PasswordRequired -> Res.string.auth_form_error_password_required
    AuthFormError.PasswordsDoNotMatch -> Res.string.auth_form_error_passwords_do_not_match
}
