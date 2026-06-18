@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.itemdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.franzueto.fluxit.core.designsystem.components.FluxItPrimaryButton
import dev.franzueto.fluxit.core.designsystem.components.FluxItScaffold
import dev.franzueto.fluxit.core.designsystem.components.FluxItTopBarCentered
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography
import dev.franzueto.fluxit.shared.state.store.ItemDetailIntent
import dev.franzueto.fluxit.shared.state.store.ItemDetailState
import dev.franzueto.fluxit.shared.state.store.LoadState

/**
 * One-shot-effect chrome for [ItemDetailScreen] (error banner, §5 confirm-discard
 * alert, §4 permission banner), bundled so the screen signature stays under the
 * detekt parameter cap (cf. `CreateListChrome`).
 */
data class ItemDetailChrome(
    val error: String? = null,
    val confirmDiscard: Boolean = false,
    val onDiscard: () -> Unit = {},
    val onKeepEditing: () -> Unit = {},
    val permissionBanner: PermissionTarget? = null,
    val onOpenSettings: () -> Unit = {},
)

/**
 * Stateless Edit-Item screen (plan/10 §1): renders [ItemDetailState] and forwards
 * actions through [onIntent]. Composed from `core-designsystem` primitives only
 * (§11 literal-ban). One-shot effects are handled in [ItemDetailRoute] and surfaced
 * back via [chrome], so this composable stays pure state-in → UI-out.
 *
 * **§1 divergence:** Save lives in the sticky bottom dock (a `FluxItPrimaryButton`),
 * not a top-bar text trailing — the DS centered top bar exposes only an *icon*
 * trailing with no disabled state, so this mirrors Phase 09's `SubmitDock`.
 */
@Composable
fun ItemDetailScreen(
    state: ItemDetailState,
    onIntent: OnItemDetailIntent,
    chrome: ItemDetailChrome = ItemDetailChrome(),
) {
    FluxItScaffold(
        topBar = {
            FluxItTopBarCentered(
                title = "Edit Item",
                backLabel = "Back",
                // System/UI back routes through the store's dirty check (§5).
                onBack = { onIntent(ItemDetailIntent.BackClicked) },
            )
        },
        bottomBar = { SaveDock(state = state, onIntent = onIntent, error = chrome.error) },
    ) { padding ->
        when (val item = state.item) {
            is LoadState.Loaded -> FormBody(state = state, onIntent = onIntent, chrome = chrome, padding = padding)
            is LoadState.Error -> CenteredMessage(message = item.message, padding = padding)
            else -> CenteredLoading(padding = padding)
        }
    }

    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = { onIntent(ItemDetailIntent.CancelDelete) },
            title = { Text("Delete this item?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton(onClick = { onIntent(ItemDetailIntent.ConfirmDelete) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { onIntent(ItemDetailIntent.CancelDelete) }) { Text("Cancel") } },
        )
    }

    if (chrome.confirmDiscard) {
        AlertDialog(
            onDismissRequest = chrome.onKeepEditing,
            title = { Text("Discard changes?") },
            text = { Text("Your edits to this item will be lost.") },
            confirmButton = { TextButton(onClick = chrome.onDiscard) { Text("Discard") } },
            dismissButton = { TextButton(onClick = chrome.onKeepEditing) { Text("Keep editing") } },
        )
    }

    PhotoSourceSheet(state = state, onIntent = onIntent)
}

@Composable
private fun FormBody(
    state: ItemDetailState,
    onIntent: OnItemDetailIntent,
    chrome: ItemDetailChrome,
    padding: PaddingValues,
) {
    val item = (state.item as LoadState.Loaded).value
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FluxItSpacing.containerPadding, vertical = FluxItSpacing.scaleLg),
        verticalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleXl),
    ) {
        GeneralInfoSection(state = state, onIntent = onIntent)
        PhotoSection(state = state, onIntent = onIntent)
        if (chrome.permissionBanner != null) {
            PermissionBanner(target = chrome.permissionBanner, onOpenSettings = chrome.onOpenSettings)
        }
        DeleteSection(onIntent = onIntent)
        LastEditedFooter(updatedAt = item.updatedAt)
    }
}

@Composable
private fun SaveDock(
    state: ItemDetailState,
    onIntent: OnItemDetailIntent,
    error: String?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = FluxItSpacing.containerPadding, vertical = FluxItSpacing.scaleLg),
        verticalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleSm),
    ) {
        if (error != null) {
            ErrorBanner(message = error)
        }
        FluxItPrimaryButton(
            label = saveLabel(state.submitting),
            onClick = { onIntent(ItemDetailIntent.SaveClicked) },
            enabled = saveEnabled(state),
        )
    }
}

@Composable
private fun CenteredLoading(padding: PaddingValues) {
    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = FluxItColors.primaryBlue)
    }
}

@Composable
private fun CenteredMessage(
    message: String,
    padding: PaddingValues,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding).padding(FluxItSpacing.containerPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, style = FluxItTypography.bodyMd, color = FluxItColors.textMuted)
    }
}
