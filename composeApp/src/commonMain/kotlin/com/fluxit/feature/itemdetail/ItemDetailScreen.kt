package com.fluxit.feature.itemdetail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import com.fluxit.ui.components.decodeImageFile
import com.fluxit.ui.theme.FluxCardShape
import com.fluxit.ui.theme.FluxColors
import com.fluxit.ui.theme.FluxSpacing
import com.fluxit.ui.theme.FluxType
import kotlinx.datetime.Instant
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ItemDetailScreen(
    itemId: String,
    onBack: () -> Unit,
    viewModel: ItemDetailViewModel = koinViewModel { parametersOf(itemId) },
) {
    val state by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.closed) {
        if (state.closed) onBack()
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = FluxColors.Surface,
            title = { Text("Delete item?", color = FluxColors.TextPrimary) },
            text = { Text("This can't be undone.", color = FluxColors.TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteItem()
                }) { Text("Delete", color = FluxColors.AccentRose) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = FluxColors.PrimaryBlue)
                }
            },
        )
    }

    Scaffold(
        containerColor = FluxColors.Background,
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
                    Icon(Icons.Outlined.ChevronLeft, contentDescription = "Back", tint = FluxColors.PrimaryBlue)
                    Text(state.listName, color = FluxColors.PrimaryBlue, style = FluxType.BodyMd, maxLines = 1)
                }
                Text(
                    "Edit Item",
                    style = FluxType.TitleMd,
                    color = FluxColors.TextPrimary,
                    modifier = Modifier.align(Alignment.Center),
                )
                Text(
                    "Save",
                    style = FluxType.BodyMd,
                    color = if (state.canSave) FluxColors.PrimaryBlue else FluxColors.TextMuted,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clickable(enabled = state.canSave, onClick = viewModel::save)
                        .padding(horizontal = FluxSpacing.ContainerPadding, vertical = 8.dp),
                )
            }

            Text(
                "General Info",
                style = FluxType.TitleMd,
                color = FluxColors.TextPrimary,
                modifier = Modifier.padding(horizontal = FluxSpacing.ContainerPadding, vertical = 8.dp),
            )

            SectionLabel("ITEM NAME")
            FluxTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                singleLine = true,
            )

            SectionLabel("DESCRIPTION")
            FluxTextField(
                value = state.description,
                onValueChange = viewModel::onDescriptionChange,
                singleLine = false,
                minHeight = 120.dp,
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
                    "Item Photo",
                    style = FluxType.TitleMd,
                    color = FluxColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    modifier = Modifier
                        .clickable(enabled = !state.isPickingPhoto, onClick = viewModel::pickPhoto)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        tint = FluxColors.PrimaryBlue,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("Update", color = FluxColors.PrimaryBlue, style = FluxType.LabelSm)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluxSpacing.ContainerPadding)
                    .aspectRatio(16f / 9f)
                    .clip(FluxCardShape)
                    .background(FluxColors.Surface),
                contentAlignment = Alignment.Center,
            ) {
                val path = state.photoPath
                val bitmap = remember(path) { path?.let(::decodeImageFile) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Item photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text("No photo yet", style = FluxType.BodyMd, color = FluxColors.TextMuted)
                }
            }
            if (state.photoPath != null) {
                Text(
                    "Remove photo",
                    style = FluxType.LabelSm,
                    color = FluxColors.AccentRose,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable(onClick = viewModel::removePhoto)
                        .padding(8.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            OutlinedButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluxSpacing.ContainerPadding)
                    .height(52.dp),
                shape = FluxCardShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, FluxColors.AccentRose.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FluxColors.AccentRose),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Delete Item", style = FluxType.BodyMd)
            }

            state.item?.let { item ->
                Text(
                    "Last edited on ${formatDate(item.updatedAt)}",
                    style = FluxType.LabelSm,
                    color = FluxColors.TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun formatDate(epochMillis: Long): String {
    val date = Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault()).date
    val month = when (date.month) {
        Month.JANUARY -> "Jan"; Month.FEBRUARY -> "Feb"; Month.MARCH -> "Mar"
        Month.APRIL -> "Apr"; Month.MAY -> "May"; Month.JUNE -> "Jun"
        Month.JULY -> "Jul"; Month.AUGUST -> "Aug"; Month.SEPTEMBER -> "Sep"
        Month.OCTOBER -> "Oct"; Month.NOVEMBER -> "Nov"; Month.DECEMBER -> "Dec"
        else -> date.month.name.take(3)
    }
    return "$month ${date.dayOfMonth}, ${date.year}"
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = FluxType.LabelSm.copy(letterSpacing = 1.5.sp),
        color = FluxColors.TextMuted,
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
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FluxSpacing.ContainerPadding)
            .height(minHeight.coerceAtLeast(56.dp).let { if (singleLine) 56.dp else it }),
        singleLine = singleLine,
        shape = FluxCardShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = FluxColors.Surface,
            unfocusedContainerColor = FluxColors.Surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = FluxColors.PrimaryBlue,
            focusedTextColor = FluxColors.TextPrimary,
            unfocusedTextColor = FluxColors.TextPrimary,
        ),
    )
}
