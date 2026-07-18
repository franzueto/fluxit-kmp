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

public data class UndoSnackbarState(
    val title: String,
    val progress: Float,
) {
    /** Alias so `ListDetailRoute` can build it positionally like the dashboard. */
    val listName: String get() = title
}

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
