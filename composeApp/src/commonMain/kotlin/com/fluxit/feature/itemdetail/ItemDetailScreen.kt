package com.fluxit.feature.itemdetail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxit.data.PhotoContent
import com.fluxit.ui.components.decodeImageBytes
import com.fluxit.ui.components.OperationErrorFeedback
import com.fluxit.ui.theme.FluxCardShape
import com.fluxit.ui.theme.FluxSpacing
import com.fluxit.ui.theme.FluxType
import fluxit.composeapp.generated.resources.*
import kotlin.time.Instant
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ItemDetailScreen(
    listId: String,
    itemId: String,
    onBack: () -> Unit,
    viewModel: ItemDetailViewModel = koinViewModel { parametersOf(listId, itemId) },
) {
    val state by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    val operationInFlight = state.isSaving || state.isDeletingItem || state.isPhotoBusy

    LaunchedEffect(state.closed) {
        if (state.closed) onBack()
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = {
                Text(
                    stringResource(Res.string.dialog_delete_item_title),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    stringResource(Res.string.dialog_delete_item_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteItem()
                    },
                    enabled = !operationInFlight,
                ) {
                    Text(stringResource(Res.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(Res.string.action_cancel), color = MaterialTheme.colorScheme.primary)
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Top bar: back, title, save
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .clickable(onClick = onBack)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.ChevronLeft,
                        contentDescription = stringResource(Res.string.action_back),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        state.listName,
                        color = MaterialTheme.colorScheme.primary,
                        style = FluxType.BodyMd,
                        maxLines = 1,
                    )
                }
                Text(
                    stringResource(Res.string.edit_item_title),
                    style = FluxType.TitleMd,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.align(Alignment.Center),
                )
                Text(
                    stringResource(Res.string.action_save),
                    style = FluxType.BodyMd,
                    color = if (state.canSave && !operationInFlight) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clickable(enabled = state.canSave && !operationInFlight, onClick = viewModel::save)
                        .padding(horizontal = FluxSpacing.ContainerPadding, vertical = 8.dp),
                )
            }

            Text(
                stringResource(Res.string.general_info_title),
                style = FluxType.TitleMd,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = FluxSpacing.ContainerPadding, vertical = 8.dp),
            )

            SectionLabel(stringResource(Res.string.section_item_name))
            FluxTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                singleLine = true,
                enabled = !operationInFlight,
            )

            SectionLabel(stringResource(Res.string.section_description))
            FluxTextField(
                value = state.description,
                onValueChange = viewModel::onDescriptionChange,
                singleLine = false,
                minHeight = 120.dp,
                enabled = !operationInFlight,
            )

            // Photo section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluxSpacing.ContainerPadding, vertical = 12.dp)
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.item_photo_title),
                    style = FluxType.TitleMd,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    modifier = Modifier
                        .clickable(enabled = !operationInFlight, onClick = viewModel::pickPhoto)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        stringResource(Res.string.action_update),
                        color = MaterialTheme.colorScheme.primary,
                        style = FluxType.LabelSm,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluxSpacing.ContainerPadding)
                    .aspectRatio(16f / 9f)
                    .clip(FluxCardShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                val preview = state.photoPreview
                val bitmap = remember(preview) {
                    when (preview) {
                        is PhotoContent.Bytes -> decodeImageBytes(preview.bytes)
                        null -> null
                    }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(Res.string.content_description_item_photo),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else if (!state.isPhotoBusy) {
                    Text(
                        stringResource(Res.string.no_photo_yet),
                        style = FluxType.BodyMd,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Loading/progress state, shown for both a replace (isPickingPhoto)
                // and a remove (isRemovingPhoto) - overlaid on top of whatever preview (old
                // photo, if any) is currently showing, so the old photo stays visible while
                // its replacement/removal is in flight rather than flashing to a blank state.
                if (state.isPhotoBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                    )
                }
            }

            val photoError = state.photoOperationFailed
            if (photoError != null) {
                PhotoErrorRow(
                    kind = photoError,
                    enabled = !operationInFlight,
                    canRetry = state.photoOperationError?.canRetry != false,
                    onRetry = viewModel::retryPhotoOperation,
                    onDismiss = viewModel::dismissPhotoError,
                )
            } else if (state.photoRef != null) {
                Text(
                    stringResource(Res.string.action_remove_photo),
                    style = FluxType.LabelSm,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable(enabled = !operationInFlight, onClick = viewModel::removePhoto)
                        .padding(8.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            state.deleteError?.let { error ->
                OperationErrorFeedback(
                    message = stringResource(Res.string.operation_delete_item_failed),
                    error = error,
                    operationInFlight = operationInFlight,
                    onRetry = viewModel::retryDeleteItem,
                    onDismiss = viewModel::dismissDeleteError,
                    modifier = Modifier.padding(
                        start = FluxSpacing.ContainerPadding,
                        end = FluxSpacing.ContainerPadding,
                        bottom = 12.dp,
                    ),
                )
            }

            OutlinedButton(
                onClick = { showDeleteDialog = true },
                enabled = !operationInFlight,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluxSpacing.ContainerPadding)
                    .height(52.dp),
                shape = FluxCardShape,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                ),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(Res.string.action_delete_item), style = FluxType.BodyMd)
            }

            state.item?.let { item ->
                Text(
                    stringResource(Res.string.last_edited_on, formatDate(item.updatedAt)),
                    style = FluxType.LabelSm,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            }
        }
        state.saveError?.let { error ->
            OperationErrorFeedback(
                message = stringResource(Res.string.operation_save_item_failed),
                error = error,
                operationInFlight = operationInFlight,
                onRetry = viewModel::retrySave,
                onDismiss = viewModel::dismissSaveError,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = FluxSpacing.ContainerPadding, vertical = 8.dp),
            )
        }
    }
}

/**
 * Shown in place of the "Remove photo" affordance whenever
 * [ItemDetailUiState.photoOperationFailed] is non-null - a failed replace or remove, with a
 * message scoped to which operation failed (see [PhotoOperationKind]) and Retry/Dismiss
 * actions wired to [ItemDetailViewModel.retryPhotoOperation]/
 * [ItemDetailViewModel.dismissPhotoError]. Both actions are disabled while [enabled] is false
 * (i.e. while a retry is already in flight), mirroring `SessionGate`'s established
 * busy-disables-actions pattern for its own retry row.
 */
@Composable
private fun PhotoErrorRow(
    kind: PhotoOperationKind,
    enabled: Boolean,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FluxSpacing.ContainerPadding, vertical = 8.dp),
    ) {
        Text(
            stringResource(kind.messageResource()),
            style = FluxType.LabelSm,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            if (canRetry) {
                Text(
                    stringResource(Res.string.action_try_again),
                    style = FluxType.LabelSm,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(enabled = enabled, onClick = onRetry)
                        .padding(8.dp),
                )
            }
            Text(
                stringResource(Res.string.action_dismiss),
                style = FluxType.LabelSm,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(enabled = enabled, onClick = onDismiss)
                    .padding(8.dp),
            )
        }
    }
}

private fun PhotoOperationKind.messageResource() = when (this) {
    PhotoOperationKind.REPLACE -> Res.string.photo_replace_failed_message
    PhotoOperationKind.REMOVE -> Res.string.photo_remove_failed_message
}

@Composable
private fun formatDate(epochMillis: Long): String {
    val date = Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault()).date
    val month = when (date.month) {
        Month.JANUARY -> Res.string.month_january_short
        Month.FEBRUARY -> Res.string.month_february_short
        Month.MARCH -> Res.string.month_march_short
        Month.APRIL -> Res.string.month_april_short
        Month.MAY -> Res.string.month_may_short
        Month.JUNE -> Res.string.month_june_short
        Month.JULY -> Res.string.month_july_short
        Month.AUGUST -> Res.string.month_august_short
        Month.SEPTEMBER -> Res.string.month_september_short
        Month.OCTOBER -> Res.string.month_october_short
        Month.NOVEMBER -> Res.string.month_november_short
        Month.DECEMBER -> Res.string.month_december_short
    }
    return stringResource(
        Res.string.date_month_day_year,
        stringResource(month),
        date.day,
        date.year,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = FluxType.LabelSm.copy(letterSpacing = 1.5.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = FluxSpacing.ContainerPadding,
            end = FluxSpacing.ContainerPadding,
            top = 12.dp,
            bottom = 8.dp,
        ),
    )
}

@Composable
private fun FluxTextField(
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean,
    minHeight: androidx.compose.ui.unit.Dp = 56.dp,
    enabled: Boolean = true,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FluxSpacing.ContainerPadding)
            .height(minHeight.coerceAtLeast(56.dp).let { if (singleLine) 56.dp else it }),
        singleLine = singleLine,
        shape = FluxCardShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}
