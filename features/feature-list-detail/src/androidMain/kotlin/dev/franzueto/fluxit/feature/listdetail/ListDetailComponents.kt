@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.listdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.franzueto.fluxit.core.designsystem.components.FluxItCard
import dev.franzueto.fluxit.core.designsystem.components.FluxItCompletedListItem
import dev.franzueto.fluxit.core.designsystem.components.FluxItProgressBar
import dev.franzueto.fluxit.core.designsystem.components.FluxItSwipeRow
import dev.franzueto.fluxit.core.designsystem.components.FluxItToBuyListItem
import dev.franzueto.fluxit.core.designsystem.icons.Check
import dev.franzueto.fluxit.core.designsystem.icons.ChevronRight
import dev.franzueto.fluxit.core.designsystem.icons.FluxItIcons
import dev.franzueto.fluxit.core.designsystem.icons.Trash
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography
import dev.franzueto.fluxit.shared.domain.model.Item
import dev.franzueto.fluxit.shared.domain.model.ItemsSection
import dev.franzueto.fluxit.shared.state.store.ListDetailIntent

/**
 * Drives the undo snackbar (plan/08 §6): the deleted item's [title] and the window
 * [progress] (1f → 0f over the 5s countdown). Held by [ListDetailRoute] and passed
 * into the stateless [ListDetailScreen]. A module-local twin of the dashboard's
 * type — the §12 Konsist rule forbids importing across feature modules.
 */
public data class UndoSnackbarState(
    val title: String,
    val progress: Float,
) {
    /** Alias so `ListDetailRoute` can build it positionally like the dashboard. */
    val listName: String get() = title
}

/**
 * Effect-driven chrome for the List Detail screen (plan/08 §3/§4/§6): the undo +
 * error snackbars and the list-actions sheet visibility. Bundled so the stateless
 * [ListDetailScreen] stays under the 8-param detekt cap while still being a pure
 * state-in → UI-out function (and snapshot-renderable with synthetic chrome).
 */
public data class ListDetailChrome(
    val undo: UndoSnackbarState? = null,
    val onUndo: () -> Unit = {},
    val error: String? = null,
    val onErrorDismiss: () -> Unit = {},
    val showMenu: Boolean = false,
    val onDismissMenu: () -> Unit = {},
)

@Composable
internal fun UndoSnackbar(
    state: UndoSnackbarState,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FluxItCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleSm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Deleted \"${state.title}\"",
                    style = FluxItTypography.bodyMd,
                    color = FluxItColors.textPrimary,
                )
                Text(
                    text = "Undo",
                    style = FluxItTypography.titleMd,
                    color = FluxItColors.primaryBlue,
                    modifier = Modifier.clickable(onClick = onUndo),
                )
            }
            FluxItProgressBar(progress = state.progress)
        }
    }
}

@Composable
internal fun ErrorSnackbar(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FluxItCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = message, style = FluxItTypography.bodyMd, color = FluxItColors.textPrimary)
            Text(
                text = "Dismiss",
                style = FluxItTypography.titleMd,
                color = FluxItColors.accentRose,
                modifier = Modifier.clickable(onClick = onDismiss),
            )
        }
    }
}

/**
 * Completion header (plan/08 §1): "LIST COMPLETION" caption + "{completed}/{total}"
 * + a full-width progress bar. Lives outside the lazy container so a single row's
 * completion flip doesn't recompose the rows (§7).
 */
@Composable
internal fun CompletionHeader(
    section: ItemsSection,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = FluxItSpacing.containerPadding, vertical = FluxItSpacing.scaleSm),
        verticalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleSm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "LIST COMPLETION", style = FluxItTypography.captionXs, color = FluxItColors.textMuted)
            Text(
                text = "${section.completedCount}/${section.total}",
                style = FluxItTypography.bodyMd,
                color = FluxItColors.textPrimary,
            )
        }
        FluxItProgressBar(progress = completionFraction(section))
    }
}

@Composable
internal fun ToBuyRow(
    item: Item,
    onIntent: OnListDetailIntent,
) {
    FluxItSwipeRow(onDelete = { onIntent(ListDetailIntent.ItemDeleteClicked(item.id)) }, deleteIcon = FluxItIcons.Trash) {
        FluxItToBuyListItem(
            title = item.title,
            subtitle = item.subtitle,
            onToggle = { onIntent(ListDetailIntent.ItemCompletionToggled(item.id)) },
            onClick = { onIntent(ListDetailIntent.ItemTapped(item.id)) },
            trailingIcon = FluxItIcons.ChevronRight,
        )
    }
}

@Composable
internal fun CompletedRow(
    item: Item,
    onIntent: OnListDetailIntent,
) {
    FluxItSwipeRow(onDelete = { onIntent(ListDetailIntent.ItemDeleteClicked(item.id)) }, deleteIcon = FluxItIcons.Trash) {
        // Trash trailing is omitted (§2 — swipe-to-delete handles it); no chevron.
        FluxItCompletedListItem(
            title = item.title,
            checkIcon = FluxItIcons.Check,
            onToggle = { onIntent(ListDetailIntent.ItemCompletionToggled(item.id)) },
            onClick = { onIntent(ListDetailIntent.ItemTapped(item.id)) },
        )
    }
}

/** Completion fraction (0f‥1f) for the progress bar; 0 when the list is empty. */
internal fun completionFraction(section: ItemsSection): Float =
    if (section.total == 0) 0f else section.completedCount.toFloat() / section.total
