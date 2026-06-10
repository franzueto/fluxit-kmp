package com.fluxit.feature.createlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.ui.components.toImageVector
import com.fluxit.ui.theme.FluxCardShape
import com.fluxit.ui.theme.FluxColors
import com.fluxit.ui.theme.FluxSpacing
import com.fluxit.ui.theme.FluxType
import com.fluxit.ui.theme.toColor
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun CreateListScreen(
    editingId: String?,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
    viewModel: CreateListViewModel = koinViewModel { parametersOf(editingId) },
) {
    val state by viewModel.uiState.collectAsState()
    var showDiscardDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.savedListId) {
        val saved = state.savedListId ?: return@LaunchedEffect
        if (state.isEditMode) onDismiss() else onCreated(saved)
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            containerColor = FluxColors.Surface,
            title = { Text("Discard changes?", color = FluxColors.TextPrimary) },
            text = { Text("Your changes will be lost.", color = FluxColors.TextMuted) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Discard", color = FluxColors.AccentRose) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep editing", color = FluxColors.PrimaryBlue)
                }
            },
        )
    }

    Scaffold(
        containerColor = FluxColors.Background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Box(modifier = Modifier.background(FluxColors.Background).padding(FluxSpacing.ContainerPadding)) {
                Button(
                    onClick = viewModel::save,
                    enabled = state.isValid && !state.isSaving && (!state.isEditMode || state.isDirty),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = FluxCardShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FluxColors.PrimaryBlue,
                        contentColor = Color.White,
                        disabledContainerColor = FluxColors.Surface,
                        disabledContentColor = FluxColors.TextMuted,
                    ),
                ) {
                    Text(if (state.isEditMode) "Save" else "Create List", style = FluxType.TitleMd)
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Top bar
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    "Cancel",
                    color = FluxColors.PrimaryBlue,
                    style = FluxType.BodyMd,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .clickable { if (state.isDirty) showDiscardDialog = true else onDismiss() }
                        .padding(horizontal = FluxSpacing.ContainerPadding, vertical = 8.dp),
                )
                Text(
                    if (state.isEditMode) "Edit List" else "New List",
                    style = FluxType.TitleMd,
                    color = FluxColors.TextPrimary,
                    modifier = Modifier.align(Alignment.Center),
                    textAlign = TextAlign.Center,
                )
            }

            SectionLabel("LIST NAME")
            TextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluxSpacing.ContainerPadding),
                placeholder = { Text("e.g., Summer Trip", color = FluxColors.TextMuted) },
                singleLine = true,
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
            if (state.name.length >= MAX_LIST_NAME_LENGTH) {
                Text(
                    "Maximum $MAX_LIST_NAME_LENGTH characters",
                    style = FluxType.LabelSm,
                    color = FluxColors.AccentRose,
                    modifier = Modifier.padding(horizontal = FluxSpacing.ContainerPadding, vertical = 4.dp),
                )
            }

            SectionLabel("CHOOSE ICON")
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(horizontal = FluxSpacing.ContainerPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                userScrollEnabled = false,
            ) {
                items(ListIcon.entries) { icon ->
                    val selected = state.icon == icon
                    Box(
                        modifier = Modifier
                            .height(88.dp)
                            .background(
                                if (selected) FluxColors.PrimaryBlue.copy(alpha = 0.15f) else FluxColors.Surface,
                                FluxCardShape,
                            )
                            .then(
                                if (selected) Modifier.border(2.dp, FluxColors.PrimaryBlue, FluxCardShape)
                                else Modifier
                            )
                            .clickable { viewModel.onIconChange(icon) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            icon.toImageVector(),
                            contentDescription = icon.name,
                            tint = if (selected) FluxColors.PrimaryBlue else FluxColors.TextPrimary,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }

            SectionLabel("LIST COLOR")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluxSpacing.ContainerPadding),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ListColor.entries.forEach { colorToken ->
                    val selected = state.color == colorToken
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .then(
                                if (selected) Modifier.border(2.dp, FluxColors.TextPrimary, CircleShape)
                                else Modifier
                            )
                            .padding(5.dp)
                            .background(colorToken.toColor(), CircleShape)
                            .clickable { viewModel.onColorChange(colorToken) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
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
            top = 24.dp,
            bottom = 8.dp,
        ),
    )
}
