package com.fluxit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fluxit.data.remote.ApplicationError
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.ui.theme.FluxCardShape
import com.fluxit.ui.theme.FluxType
import fluxit.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

data class OperationErrorActionState(
    val showRetry: Boolean,
    val retryEnabled: Boolean,
    val dismissEnabled: Boolean,
)

fun operationErrorActionState(
    error: ApplicationError,
    operationInFlight: Boolean,
): OperationErrorActionState = OperationErrorActionState(
    showRetry = error.canRetry,
    retryEnabled = error.canRetry && !operationInFlight,
    dismissEnabled = !operationInFlight,
)

@Composable
fun OperationErrorFeedback(
    message: String,
    error: ApplicationError,
    operationInFlight: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = operationErrorActionState(error, operationInFlight)
    Column(
        modifier = modifier
            .testTag("operationErrorFeedback")
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, FluxCardShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = FluxType.BodyMd,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("operationErrorMessage"),
        )
        Text(
            stringResource(error.code.messageResource()),
            style = FluxType.LabelSm,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .testTag("operationErrorDetail")
                .padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            if (actions.showRetry) {
                Text(
                    stringResource(Res.string.action_try_again),
                    style = FluxType.LabelSm,
                    color = if (actions.retryEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .testTag("operationErrorRetry")
                        .clickable(enabled = actions.retryEnabled, onClick = onRetry)
                        .padding(8.dp),
                )
            }
            Text(
                stringResource(Res.string.action_dismiss),
                style = FluxType.LabelSm,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag("operationErrorDismiss")
                    .clickable(enabled = actions.dismissEnabled, onClick = onDismiss)
                    .padding(8.dp),
            )
        }
    }
}

private fun RepositoryErrorCode.messageResource(): StringResource = when (this) {
    RepositoryErrorCode.SESSION_REQUIRED -> Res.string.operation_error_session_required
    RepositoryErrorCode.FORBIDDEN -> Res.string.operation_error_forbidden
    RepositoryErrorCode.OFFLINE -> Res.string.operation_error_offline
    RepositoryErrorCode.TIMEOUT -> Res.string.operation_error_timeout
    RepositoryErrorCode.NOT_FOUND -> Res.string.operation_error_not_found
    RepositoryErrorCode.CONFLICT -> Res.string.operation_error_conflict
    RepositoryErrorCode.INVALID_DATA -> Res.string.operation_error_invalid_data
    RepositoryErrorCode.QUOTA -> Res.string.operation_error_quota
    RepositoryErrorCode.UNKNOWN -> Res.string.operation_error_unknown
}
