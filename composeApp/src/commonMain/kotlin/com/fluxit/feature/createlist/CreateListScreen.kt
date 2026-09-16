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
import androidx.compose.material3.MaterialTheme
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
import com.fluxit.ui.theme.FluxSpacing
import com.fluxit.ui.theme.FluxType
import com.fluxit.ui.theme.toColor
import fluxit.composeapp.generated.resources.*
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.jetbrains.compose.resources.stringResource

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
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(Res.string.dialog_discard_changes_title), color = MaterialTheme.colorScheme.onSurface) },
            text = { Text(stringResource(Res.string.dialog_discard_changes_message), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.action_discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(Res.string.action_keep_editing), color = MaterialTheme.colorScheme.primary)
                }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .padding(FluxSpacing.ContainerPadding),
            ) {
                Button(
                    onClick = viewModel::save,
                    enabled = state.isValid && !state.isSaving && (!state.isEditMode || state.isDirty),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = FluxCardShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(
                        stringResource(
                            if (state.isEditMode) Res.string.action_save else Res.string.action_create_list
                        ),
                        style = FluxType.TitleMd,
                    )
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
                    stringResource(Res.string.action_cancel),
                    color = MaterialTheme.colorScheme.primary,
                    style = FluxType.BodyMd,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .clickable { if (state.isDirty) showDiscardDialog = true else onDismiss() }
                        .padding(horizontal = FluxSpacing.ContainerPadding, vertical = 8.dp),
                )
                Text(
                    stringResource(
                        if (state.isEditMode) Res.string.edit_list_title else Res.string.create_list_title
                    ),
                    style = FluxType.TitleMd,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.align(Alignment.Center),
                    textAlign = TextAlign.Center,
                )
            }

            SectionLabel(stringResource(Res.string.section_list_name))
            TextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluxSpacing.ContainerPadding),
                placeholder = {
                    Text(
                        stringResource(Res.string.list_name_placeholder),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                singleLine = true,
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
            if (state.name.length >= MAX_LIST_NAME_LENGTH) {
                Text(
                    stringResource(Res.string.maximum_characters, MAX_LIST_NAME_LENGTH),
                    style = FluxType.LabelSm,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = FluxSpacing.ContainerPadding, vertical = 4.dp),
                )
            }

            SectionLabel(stringResource(Res.string.section_choose_icon))
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
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceContainer,
                                FluxCardShape,
                            )
                            .then(
                                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, FluxCardShape)
                                else Modifier
                            )
                            .clickable { viewModel.onIconChange(icon) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            icon.toImageVector(),
                            contentDescription = icon.localizedName(),
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }

            SectionLabel(stringResource(Res.string.section_list_color))
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
                                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
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
private fun ListIcon.localizedName(): String = stringResource(
    when (this) {
        ListIcon.CART -> Res.string.icon_cart
        ListIcon.TRAVEL -> Res.string.icon_travel
        ListIcon.WORK -> Res.string.icon_work
        ListIcon.HOME -> Res.string.icon_home
        ListIcon.GIFT -> Res.string.icon_gift
        ListIcon.FOOD -> Res.string.icon_food
        ListIcon.FITNESS -> Res.string.icon_fitness
        ListIcon.STAR -> Res.string.icon_star
    }
)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = FluxType.LabelSm.copy(letterSpacing = 1.5.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = FluxSpacing.ContainerPadding,
            end = FluxSpacing.ContainerPadding,
            top = 24.dp,
            bottom = 8.dp,
        ),
    )
}
