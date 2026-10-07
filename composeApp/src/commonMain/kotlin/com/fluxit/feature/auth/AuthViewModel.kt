package com.fluxit.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxit.domain.auth.AuthError
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthResult
import com.fluxit.domain.auth.errorOrNull
import com.fluxit.domain.auth.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The three interactive flows: email + password only, no provider. */
enum class AuthMode { SignIn, SignUp, Recover }

/**
 * Client-side input problems, kept separate from [AuthError] so a local mistake is never
 * reported as a backend failure.
 */
enum class AuthFormError { EmailRequired, PasswordRequired, PasswordsDoNotMatch }

data class AuthUiState(
    val mode: AuthMode = AuthMode.SignIn,
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isSubmitting: Boolean = false,
    val formError: AuthFormError? = null,
    val authError: AuthError? = null,
    val recoveryEmailSentTo: String? = null,
) {
    /** Recovery needs no password; the other two modes do. */
    val requiresPassword: Boolean get() = mode != AuthMode.Recover

    /** Only sign-up asks for confirmation, so a typo cannot lock a new account out. */
    val requiresConfirmation: Boolean get() = mode == AuthMode.SignUp

    val canSubmit: Boolean
        get() = !isSubmitting &&
            email.isNotBlank() &&
            (!requiresPassword || password.isNotBlank()) &&
            (!requiresConfirmation || confirmPassword.isNotBlank())
}

/**
 * Drives the signed-out authentication UI: sign-up, sign-in and password
 * recovery, all email + password.
 *
 * Success is deliberately *not* reported through this state for sign-in/sign-up: the
 * session flow is the single source of truth, so the gate reacts to
 * `AuthSession.Authenticated` and this screen simply disappears. Only recovery, which
 * does not change the session, reports its own success.
 */
class AuthViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun onEmailChange(value: String) = clearFeedback { it.copy(email = value) }

    fun onPasswordChange(value: String) = clearFeedback { it.copy(password = value) }

    fun onConfirmPasswordChange(value: String) = clearFeedback { it.copy(confirmPassword = value) }

    /** Switches flow, dropping any stale error/success feedback and secret material. */
    fun onModeChange(mode: AuthMode) {
        if (_uiState.value.isSubmitting) return
        _uiState.value = _uiState.value.copy(
            mode = mode,
            password = "",
            confirmPassword = "",
            formError = null,
            authError = null,
            recoveryEmailSentTo = null,
        )
    }

    fun dismissFeedback() = clearFeedback { it }

    fun submit() {
        val state = _uiState.value
        if (state.isSubmitting) return
        validate(state)?.let { problem ->
            _uiState.value = state.copy(formError = problem, authError = null, recoveryEmailSentTo = null)
            return
        }
        val email = state.email.trim()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSubmitting = true,
                formError = null,
                authError = null,
                recoveryEmailSentTo = null,
            )
            val result: AuthResult = when (state.mode) {
                AuthMode.SignIn -> authRepository.signIn(email, state.password)
                AuthMode.SignUp -> authRepository.signUp(email, state.password)
                AuthMode.Recover -> authRepository.sendPasswordResetEmail(email)
            }
            val settled = _uiState.value
            _uiState.value = if (result.isSuccess) {
                settled.copy(
                    isSubmitting = false,
                    // Never keep the password around after a completed attempt.
                    password = "",
                    confirmPassword = "",
                    authError = null,
                    recoveryEmailSentTo = if (state.mode == AuthMode.Recover) email else null,
                )
            } else {
                settled.copy(isSubmitting = false, authError = result.errorOrNull)
            }
        }
    }

    private fun validate(state: AuthUiState): AuthFormError? = when {
        state.email.isBlank() -> AuthFormError.EmailRequired
        state.requiresPassword && state.password.isBlank() -> AuthFormError.PasswordRequired
        state.requiresConfirmation && state.password != state.confirmPassword ->
            AuthFormError.PasswordsDoNotMatch
        else -> null
    }

    private inline fun clearFeedback(transform: (AuthUiState) -> AuthUiState) {
        _uiState.value = transform(_uiState.value)
            .copy(formError = null, authError = null, recoveryEmailSentTo = null)
    }
}
