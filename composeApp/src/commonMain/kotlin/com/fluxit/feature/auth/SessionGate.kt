package com.fluxit.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fluxit.domain.auth.AuthError
import com.fluxit.navigation.AppNavHost
import com.fluxit.ui.theme.FluxCardShape
import com.fluxit.ui.theme.FluxSpacing
import com.fluxit.ui.theme.FluxType
import fluxit.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * The application root session gate (FB-104).
 *
 * This is the structural guarantee behind the FB-104 acceptance criterion: [AppNavHost]
 * - and therefore every screen, ViewModel and repository listener the app owns - is
 * composed **only** in the [SessionGateState.Ready] branch. While the gate is
 * [SessionGateState.Resolving] nothing user-scoped is instantiated, because the composable
 * that would instantiate it does not exist in the composition at all. `Resolving` and
 * `SignedOut` are handled by separate branches so "not known yet" can never be silently
 * treated as "signed out".
 *
 * The strength of that guarantee depends entirely on when [SessionGateViewModel] is willing
 * to report [SessionGateState.Ready]. Per FB-104-B1 it does so only once the initial
 * `restoreSession()` has returned, never on the strength of a cached credential that the
 * server has not yet validated - see that class's KDoc. Without that sequencing this
 * branch would briefly compose [AppNavHost] for a session that turns out to be invalid.
 *
 * `key(uid)` around [AppNavHost] means a change of user tears down the whole navigation
 * subtree, including its `ViewModelStore`, rather than re-using one user's composition
 * state for the next (groundwork for FB-105's A -> B isolation work).
 */
@Composable
fun SessionGate(viewModel: SessionGateViewModel = koinViewModel()) {
    val gate by viewModel.gate.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()

    when (val state = gate) {
        SessionGateState.Resolving -> SessionResolvingScreen()

        SessionGateState.SignedOut -> AuthScreen()

        is SessionGateState.ResolutionFailed -> SessionResolutionFailedScreen(
            error = state.error,
            isBusy = isBusy,
            onRetry = viewModel::retryResolution,
            onSignOutAndRetry = viewModel::signOutAndRetry,
        )

        is SessionGateState.Ready -> key(state.user.uid) {
            AppNavHost(
                accountEmail = state.user.email,
                onSignOut = viewModel::signOut,
            )
        }
    }
}

/** Shown while the session is unresolved. Reads no uid and starts no listener. */
@Composable
private fun SessionResolvingScreen() {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(FluxSpacing.ContainerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
            Text(
                stringResource(Res.string.session_resolving),
                style = FluxType.BodyMd,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = FluxSpacing.SectionGap),
            )
        }
    }
}

/**
 * Shown when session resolution itself failed.
 *
 * Two actions, deliberately: a plain retry for a transient failure, and an explicit
 * "sign out and retry". The second is required, not cosmetic - per FB-102-NB2 and its
 * iOS mirror, neither adapter signs out on a hard resolution failure, so a revoked or
 * expired credential would otherwise trap the user in this state forever, with a retry
 * button that can never succeed.
 */
@Composable
private fun SessionResolutionFailedScreen(
    error: AuthError,
    isBusy: Boolean,
    onRetry: () -> Unit,
    onSignOutAndRetry: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(FluxSpacing.ContainerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(Res.string.session_failed_title),
                style = FluxType.TitleMd,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(error.messageResource()),
                style = FluxType.BodyMd,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = FluxSpacing.StackGap),
            )
            Text(
                stringResource(Res.string.session_failed_body),
                style = FluxType.LabelSm,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = FluxSpacing.StackGap),
            )

            Button(
                onClick = onRetry,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(56.dp),
                shape = FluxCardShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(stringResource(Res.string.action_try_again), style = FluxType.TitleMd)
            }

            OutlinedButton(
                onClick = onSignOutAndRetry,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().padding(top = FluxSpacing.StackGap),
                shape = FluxCardShape,
            ) {
                Text(
                    stringResource(Res.string.action_sign_out_and_retry),
                    style = FluxType.BodyMd,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).padding(top = FluxSpacing.StackGap),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}
