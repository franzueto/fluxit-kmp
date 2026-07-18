@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.franzueto.fluxit.core.designsystem.components.FluxItCard
import dev.franzueto.fluxit.core.designsystem.components.FluxItDashboardListItem
import dev.franzueto.fluxit.core.designsystem.components.FluxItProgressBar
import dev.franzueto.fluxit.core.designsystem.components.FluxItSwipeRow
import dev.franzueto.fluxit.core.designsystem.components.toColor
import dev.franzueto.fluxit.core.designsystem.components.toImageVector
import dev.franzueto.fluxit.core.designsystem.icons.ChevronRight
import dev.franzueto.fluxit.core.designsystem.icons.FluxItIcons
import dev.franzueto.fluxit.core.designsystem.icons.More
import dev.franzueto.fluxit.core.designsystem.icons.Trash
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography
import dev.franzueto.fluxit.shared.domain.model.ListSummary
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

public data class UndoSnackbarState(
    val listName: String,
    val progress: Float,
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
                    text = "Deleted \"${state.listName}\"",
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
internal fun SkeletonList() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = FluxItSpacing.containerPadding),
        verticalArrangement = Arrangement.spacedBy(FluxItSpacing.stackGap),
    ) {
        repeat(SKELETON_ROWS) {
            FluxItDashboardListItem(
                icon = FluxItIcons.More,
                iconTint = FluxItColors.textMuted,
                title = "Loading…",
                subtitle = " ",
            )
        }
    }
}

@Composable
internal fun DashboardListRow(
    summary: ListSummary,
    now: Instant,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val subtitle = remember(summary, now) { subtitleFor(summary, now) }
    FluxItSwipeRow(onDelete = onDelete, deleteIcon = FluxItIcons.Trash) {
        FluxItDashboardListItem(
            icon = summary.icon.toImageVector(),
            iconTint = summary.color.toColor(),
            title = summary.name,
            subtitle = subtitle,
            onClick = onOpen,
            chevronIcon = FluxItIcons.ChevronRight,
        )
    }
}

internal fun subtitleFor(
    summary: ListSummary,
    now: Instant,
): String {
    if (summary.totalItems == 0) return "No items yet"
    val metadata =
        if (summary.completedItems in 1 until summary.totalItems) {
            "${summary.completedItems * 100 / summary.totalItems}% completed"
        } else {
            "Last updated ${relativeTime(summary.lastActivityAt, now)}"
        }
    return "${summary.totalItems} items · $metadata"
}

internal fun relativeTime(
    from: Instant,
    now: Instant,
): String {
    val elapsed = now - from
    return when {
        elapsed < 1.minutes -> "just now"
        elapsed < 1.hours -> "${elapsed.inWholeMinutes}m ago"
        elapsed < 1.days -> "${elapsed.inWholeHours}h ago"
        elapsed < 7.days -> "${elapsed.inWholeDays}d ago"
        else -> "${elapsed.inWholeDays / 7}w ago"
    }
}

private const val SKELETON_ROWS = 3
