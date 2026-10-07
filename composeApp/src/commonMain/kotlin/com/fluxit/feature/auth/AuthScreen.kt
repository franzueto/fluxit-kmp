package com.fluxit.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.fluxit.ui.theme.FluxCardShape
import com.fluxit.ui.theme.FluxSpacing
import com.fluxit.ui.theme.FluxType
import fluxit.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * The signed-out authentication screen: sign-in, sign-up and password recovery.
 *
 * Composed only from [SessionGate] when the session has resolved to
 * [SessionGateState.SignedOut]. It never reads a uid and never starts user-scoped work.
 */
@Composable
fun AuthScreen(
    viewModel: AuthViewModel = koinViewModel(),
    restoreTimedOut: Boolean = false,
    onRetryRestore: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    AuthScreenContent(
        state = state,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onConfirmPasswordChange = viewModel::onConfirmPasswordChange,
        onModeChange = viewModel::onModeChange,
        onSubmit = viewModel::submit,
        restoreTimedOut = restoreTimedOut,
        onRetryRestore = onRetryRestore,
    )
}

/**
 * Stateless rendering of [AuthUiState].
 *
 * Deliberately state-free so it can be driven from a test or a future preview without
 * any fake repository. This is the resolution chosen for Rather than
 * adding a preview-only `AuthRepository` fake to `commonMain` (or restructuring source
 * sets so `commonMain` can see `commonTest`'s `FakeAuthRepository`), the UI takes plain
 * data plus callbacks, so nothing in `commonMain` needs a fake at all.
 */
@Composable
fun AuthScreenContent(
    state: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onModeChange: (AuthMode) -> Unit,
    onSubmit: () -> Unit,
    restoreTimedOut: Boolean = false,
    onRetryRestore: () -> Unit = {},
) {
    var passwordVisible by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FluxSpacing.ContainerPadding),
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                stringResource(
                    when (state.mode) {
                        AuthMode.SignIn -> Res.string.auth_sign_in_title
                        AuthMode.SignUp -> Res.string.auth_sign_up_title
                        AuthMode.Recover -> Res.string.auth_recover_title
                    },
                ),
                style = FluxType.DisplayLg,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(FluxSpacing.StackGap))
            Text(
                stringResource(
                    when (state.mode) {
                        AuthMode.SignIn -> Res.string.auth_sign_in_subtitle
                        AuthMode.SignUp -> Res.string.auth_sign_up_subtitle
                        AuthMode.Recover -> Res.string.auth_recover_subtitle
                    },
                ),
                style = FluxType.BodyMd,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (restoreTimedOut) {
                Spacer(Modifier.height(FluxSpacing.SectionGap))
                SessionRestoreTimeoutNotice(onRetryRestore = onRetryRestore)
            }

            Spacer(Modifier.height(32.dp))
            AuthSectionLabel(stringResource(Res.string.section_email))
            AuthTextField(
                value = state.email,
                onValueChange = onEmailChange,
                placeholder = stringResource(Res.string.email_placeholder),
                enabled = !state.isSubmitting,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
            )

            if (state.requiresPassword) {
                Spacer(Modifier.height(FluxSpacing.SectionGap))
                AuthSectionLabel(stringResource(Res.string.section_password))
                AuthTextField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    placeholder = stringResource(Res.string.password_placeholder),
                    enabled = !state.isSubmitting,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (state.requiresConfirmation) ImeAction.Next else ImeAction.Done,
                    ),
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                )
                TextButton(
                    onClick = { passwordVisible = !passwordVisible },
                    enabled = !state.isSubmitting,
                ) {
                    Text(
                        stringResource(
                            if (passwordVisible) Res.string.action_hide else Res.string.action_show,
                        ),
                        style = FluxType.LabelSm,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (state.requiresConfirmation) {
                AuthSectionLabel(stringResource(Res.string.section_confirm_password))
                AuthTextField(
                    value = state.confirmPassword,
                    onValueChange = onConfirmPasswordChange,
                    placeholder = stringResource(Res.string.confirm_password_placeholder),
                    enabled = !state.isSubmitting,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                )
            }

            state.formError?.let { problem ->
                AuthFeedback(
                    text = stringResource(problem.messageResource()),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.authError?.let { error ->
                AuthFeedback(
                    text = stringResource(error.messageResource()),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.recoveryEmailSentTo?.let { address ->
                AuthFeedback(
                    text = stringResource(Res.string.auth_recovery_sent, address),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onSubmit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = FluxCardShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        stringResource(
                            when (state.mode) {
                                AuthMode.SignIn -> Res.string.action_sign_in
                                AuthMode.SignUp -> Res.string.action_sign_up
                                AuthMode.Recover -> Res.string.action_send_reset_link
                            },
                        ),
                        style = FluxType.TitleMd,
                    )
                }
            }

            Spacer(Modifier.height(FluxSpacing.StackGap))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FluxSpacing.RowGap),
            ) {
                when (state.mode) {
                    AuthMode.SignIn -> {
                        AuthModeLink(Res.string.auth_switch_to_sign_up, !state.isSubmitting) {
                            onModeChange(AuthMode.SignUp)
                        }
                        AuthModeLink(Res.string.auth_switch_to_recover, !state.isSubmitting) {
                            onModeChange(AuthMode.Recover)
                        }
                    }
                    AuthMode.SignUp, AuthMode.Recover -> {
                        AuthModeLink(Res.string.auth_switch_to_sign_in, !state.isSubmitting) {
                            onModeChange(AuthMode.SignIn)
                        }
                    }
                }
            }
            Spacer(Modifier.height(FluxSpacing.ContainerPadding))
        }
    }
}

@Composable
private fun AuthSectionLabel(text: String) {
    Text(
        text,
        style = FluxType.CaptionXs,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = FluxSpacing.RowGap),
    )
}

@Composable
private fun AuthFeedback(text: String, color: Color) {
    Text(
        text,
        style = FluxType.LabelSm,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(top = FluxSpacing.StackGap),
    )
}

@Composable
private fun AuthModeLink(
    label: StringResource,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(stringResource(label), style = FluxType.BodyMd, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true,
        shape = FluxCardShape,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/**
 * The restoration-timeout fallback notice: the initial session restoration exceeded
 * [SessionGateViewModel.InitialRestorationTimeout] and the gate resolved to signed-out.
 *
 * Deliberately not an error: nothing has failed as far as the user is concerned, they
 * are simply signed out and may either sign in normally or ask the app to try restoring
 * again. It is rendered above the form, in the neutral surface colour rather than the
 * error colour, for exactly that reason.
 */
@Composable
private fun SessionRestoreTimeoutNotice(onRetryRestore: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(Res.string.session_restore_timeout_notice),
            style = FluxType.BodyMd,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetryRestore) {
            Text(
                stringResource(Res.string.action_try_again),
                style = FluxType.LabelSm,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
