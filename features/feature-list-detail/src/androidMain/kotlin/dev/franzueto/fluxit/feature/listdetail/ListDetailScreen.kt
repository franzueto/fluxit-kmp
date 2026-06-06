@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.listdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.franzueto.fluxit.core.designsystem.components.FluxItEmptyState
import dev.franzueto.fluxit.core.designsystem.components.FluxItInlineComposer
import dev.franzueto.fluxit.core.designsystem.components.FluxItScaffold
import dev.franzueto.fluxit.core.designsystem.components.FluxItSectionHeader
import dev.franzueto.fluxit.core.designsystem.components.FluxItTopBarCentered
import dev.franzueto.fluxit.core.designsystem.icons.FluxItIcons
import dev.franzueto.fluxit.core.designsystem.icons.More
import dev.franzueto.fluxit.core.designsystem.icons.Plus
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography
import dev.franzueto.fluxit.shared.domain.model.ItemsSection
import dev.franzueto.fluxit.shared.state.store.ListDetailIntent
import dev.franzueto.fluxit.shared.state.store.ListDetailState
import dev.franzueto.fluxit.shared.state.store.LoadState

/**
 * Stateless List Detail screen (plan/08 §1/§3): renders [ListDetailState] and
 * forwards user actions through [onIntent]. Composed entirely from
 * `core-designsystem` primitives (§12 literal-ban). The top bar (variant B) owns
 * the back button + ⋯ menu; the completion header + progress bar live above the
 * lazy sections (§7); the composer docks at the bottom above the keyboard.
 *
 * One-shot effects are handled in [ListDetailRoute] and surfaced back as
 * [undo] / [error] / [showMenu], so this composable stays pure state-in → UI-out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDetailScreen(
    state: ListDetailState,
    onIntent: OnListDetailIntent,
    onBack: () -> Unit,
    chrome: ListDetailChrome = ListDetailChrome(),
) {
    val header = state.header
    val title = (header as? LoadState.Loaded)?.value?.name ?: ""

    FluxItScaffold(
        topBar = {
            FluxItTopBarCentered(
                title = title,
                backLabel = "Lists",
                onBack = onBack,
                trailingIcon = FluxItIcons.More,
                onTrailingClick = { onIntent(ListDetailIntent.MoreClicked) },
                trailingContentDescription = "List actions",
            )
        },
        bottomBar = {
            if (header is LoadState.Loaded) {
                ComposerDock(state = state, onIntent = onIntent)
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (header) {
                LoadState.Loading -> CenteredSpinner()
                is LoadState.Error -> CenteredEmpty(title = header.message, message = null)
                LoadState.Empty,
                is LoadState.Loaded,
                -> DetailBody(state = state, onIntent = onIntent)
            }

            val undo = chrome.undo
            if (undo != null) {
                UndoSnackbar(
                    state = undo,
                    onUndo = chrome.onUndo,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = FluxItSpacing.containerPadding, vertical = FluxItSpacing.scaleLg),
                )
            }
            val error = chrome.error
            if (error != null) {
                ErrorSnackbar(
                    message = error,
                    onDismiss = chrome.onErrorDismiss,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = FluxItSpacing.containerPadding, vertical = FluxItSpacing.scaleLg),
                )
            }
        }
    }

    if (chrome.showMenu) {
        ListActionsSheet(
            onClearCompleted = { onIntent(ListDetailIntent.ClearCompletedClicked) },
            onDismiss = chrome.onDismissMenu,
        )
    }
}

@Composable
private fun DetailBody(
    state: ListDetailState,
    onIntent: OnListDetailIntent,
) {
    when (val sections = state.sections) {
        LoadState.Loading -> CenteredSpinner()
        LoadState.Empty ->
            CenteredEmpty(title = "No items yet", message = "Tap the field below to add your first item.")
        is LoadState.Error -> CenteredEmpty(title = sections.message, message = null)
        is LoadState.Loaded -> SectionsList(section = sections.value, showCompleted = state.showCompleted, onIntent = onIntent)
    }
}

@Composable
private fun SectionsList(
    section: ItemsSection,
    showCompleted: Boolean,
    onIntent: OnListDetailIntent,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        CompletionHeader(section = section)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = FluxItSpacing.containerPadding, vertical = FluxItSpacing.scaleSm),
            verticalArrangement = Arrangement.spacedBy(FluxItSpacing.stackGap),
        ) {
            if (section.active.isNotEmpty()) {
                item(key = "header-active") { FluxItSectionHeader(label = "TO BUY") }
                items(section.active, key = { it.id.value }) { ToBuyRow(item = it, onIntent = onIntent) }
            }
            if (section.completed.isNotEmpty()) {
                item(key = "header-completed") {
                    FluxItSectionHeader(
                        label = "COMPLETED",
                        trailingActionLabel = if (showCompleted) "Hide" else "Show",
                        onTrailingAction = { onIntent(ListDetailIntent.ToggleShowCompleted) },
                    )
                }
                if (showCompleted) {
                    items(section.completed, key = { it.id.value }) { CompletedRow(item = it, onIntent = onIntent) }
                }
            }
        }
    }
}

@Composable
private fun ComposerDock(
    state: ListDetailState,
    onIntent: OnListDetailIntent,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = FluxItSpacing.containerPadding, vertical = FluxItSpacing.scaleSm),
        verticalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleSm),
    ) {
        if (state.composerError != null) {
            Text(
                text = state.composerError ?: "",
                style = FluxItTypography.labelSm,
                color = FluxItColors.accentRose,
            )
        }
        FluxItInlineComposer(
            value = state.composerText,
            onValueChange = { onIntent(ListDetailIntent.ComposerTextChanged(it)) },
            onSubmit = { onIntent(ListDetailIntent.ComposerSubmit) },
            submitIcon = FluxItIcons.Plus,
            placeholder = "Add new item…",
        )
    }
}

/**
 * List-actions sheet (plan/08 §4). v1 wires **Clear completed** (with a
 * confirmation alert, §13); Edit / Star / Reminders / Delete-list entries are
 * rendered disabled — their backing intents land in Phases 09/13 and the shipped
 * [dev.franzueto.fluxit.shared.state.store.ListDetailStore] exposes no intents for
 * them yet (documented divergence from §4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListActionsSheet(
    onClearCompleted: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = FluxItSpacing.scale2xl),
        ) {
            ActionRow(label = "Edit list details", enabled = false)
            ActionRow(label = "Star list", enabled = false)
            ActionRow(label = "Reminder settings", enabled = false)
            ActionRow(label = "Clear completed", enabled = true, onClick = { confirmClear = true })
            ActionRow(label = "Delete list", enabled = false, destructive = true)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear completed?") },
            text = { Text("Completed items will be removed from this list.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearCompleted()
                    onDismiss()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ActionRow(
    label: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit = {},
) {
    val color =
        when {
            !enabled -> FluxItColors.textMuted
            destructive -> FluxItColors.accentRose
            else -> FluxItColors.textPrimary
        }
    Text(
        text = if (enabled) label else "$label (coming soon)",
        style = FluxItTypography.bodyMd,
        color = color,
        modifier =
            Modifier
                .fillMaxWidth()
                .let { if (enabled) it.clickable(onClick = onClick) else it }
                .padding(horizontal = FluxItSpacing.containerPadding, vertical = FluxItSpacing.scaleMd),
    )
}

@Composable
private fun CenteredSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredEmpty(
    title: String,
    message: String?,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        FluxItEmptyState(title = title, message = message)
    }
}
