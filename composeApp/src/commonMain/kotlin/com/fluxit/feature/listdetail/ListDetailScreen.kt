package com.fluxit.feature.listdetail

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxit.domain.FluxItem
import com.fluxit.ui.components.EmptyState
import com.fluxit.ui.components.SwipeToDeleteContainer
import com.fluxit.ui.theme.FluxCardShape
import com.fluxit.ui.theme.FluxSpacing
import com.fluxit.ui.theme.FluxType
import fluxit.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ListDetailScreen(
    listId: String,
    onBack: () -> Unit,
    onEditList: (String) -> Unit,
    onOpenItem: (String) -> Unit,
    viewModel: ListDetailViewModel = koinViewModel { parametersOf(listId) },
) {
    val state by viewModel.uiState.collectAsState()
    val undoItemId by viewModel.undoItemId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuExpanded by remember { mutableStateOf(false) }
    val itemDeletedMessage = stringResource(Res.string.message_item_deleted)
    val undoLabel = stringResource(Res.string.action_undo)

    LaunchedEffect(state.listDeleted) {
        if (state.listDeleted) onBack()
    }

    LaunchedEffect(undoItemId, itemDeletedMessage, undoLabel) {
        val id = undoItemId ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = itemDeletedMessage,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.dismissUndo()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Composer(
                text = state.composerText,
                onTextChange = viewModel::onComposerChange,
                onSubmit = viewModel::submitComposer,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.clickable(onClick = onBack).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.ChevronLeft,
                        contentDescription = stringResource(Res.string.action_back),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(Res.string.lists_title),
                        color = MaterialTheme.colorScheme.primary,
                        style = FluxType.BodyMd,
                    )
                }
                Text(
                    text = state.list?.name ?: "",
                    style = FluxType.TitleMd,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Outlined.MoreHoriz,
                            contentDescription = stringResource(Res.string.action_more),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.action_edit_list_details)) },
                            onClick = {
                                menuExpanded = false
                                onEditList(listId)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.action_clear_completed)) },
                            onClick = {
                                menuExpanded = false
                                viewModel.clearCompleted()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(Res.string.action_delete_list),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                viewModel.deleteList()
                            },
                        )
                    }
                }
            }

            // Completion header
            Column(modifier = Modifier.padding(horizontal = FluxSpacing.ContainerPadding, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.section_list_completion),
                        style = FluxType.LabelSm.copy(letterSpacing = 1.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(
                            Res.string.completion_count,
                            state.completedCount,
                            state.totalCount,
                        ),
                        style = FluxType.TitleMd,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = {
                        if (state.totalCount == 0) 0f
                        else state.completedCount.toFloat() / state.totalCount
                    },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer,
                    drawStopIndicator = {},
                )
            }

            if (state.list != null && state.totalCount == 0) {
                EmptyState(stringResource(Res.string.empty_list_items))
            }

            LazyColumn(
                modifier = Modifier.weight(1f).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(FluxSpacing.RowGap),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = FluxSpacing.ContainerPadding,
                    end = FluxSpacing.ContainerPadding,
                    bottom = 16.dp,
                ),
            ) {
                if (state.activeItems.isNotEmpty()) {
                    item(key = "header_active") {
                        SectionHeader(stringResource(Res.string.section_to_buy))
                    }
                    items(state.activeItems, key = { it.id }) { item ->
                        SwipeToDeleteContainer(onDelete = { viewModel.deleteItem(item.id) }) {
                            ItemRow(
                                item = item,
                                onToggle = { viewModel.toggleCompleted(item) },
                                onClick = { onOpenItem(item.id) },
                            )
                        }
                    }
                }
                if (state.completedItems.isNotEmpty()) {
                    item(key = "header_completed") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionHeader(
                                stringResource(Res.string.section_completed),
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(
                                    if (state.showCompleted) Res.string.action_hide else Res.string.action_show
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                style = FluxType.LabelSm,
                                modifier = Modifier.clickable { viewModel.toggleShowCompleted() }.padding(8.dp),
                            )
                        }
                    }
                    if (state.showCompleted) {
                        items(state.completedItems, key = { it.id }) { item ->
                            SwipeToDeleteContainer(onDelete = { viewModel.deleteItem(item.id) }) {
                                ItemRow(
                                    item = item,
                                    onToggle = { viewModel.toggleCompleted(item) },
                                    onClick = { onOpenItem(item.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = FluxType.LabelSm.copy(letterSpacing = 1.5.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun ItemRow(item: FluxItem, onToggle: () -> Unit, onClick: () -> Unit) {
    // Opaque composite so the swipe-to-delete background never bleeds through.
    val rowColor =
        if (item.isCompleted) {
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
                .compositeOver(MaterialTheme.colorScheme.background)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
    Surface(
        color = rowColor,
        shape = FluxCardShape,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .then(
                        if (item.isCompleted) Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                        else Modifier.border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                    )
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                if (item.isCompleted) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = stringResource(Res.string.content_description_completed),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(
                    item.title,
                    style = FluxType.BodyMd,
                    color = if (item.isCompleted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
                )
                if (!item.isCompleted && !item.description.isNullOrBlank()) {
                    Text(
                        item.description,
                        style = FluxType.LabelSm,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (!item.isCompleted) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Composer(text: String, onTextChange: (String) -> Unit, onSubmit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .padding(horizontal = FluxSpacing.ContainerPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    stringResource(Res.string.add_item_placeholder),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
            shape = CircleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
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
        Spacer(Modifier.size(12.dp))
        val enabled = text.isNotBlank()
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                    CircleShape,
                )
                .clickable(enabled = enabled, onClick = onSubmit),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Send,
                contentDescription = stringResource(Res.string.action_add_item),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
