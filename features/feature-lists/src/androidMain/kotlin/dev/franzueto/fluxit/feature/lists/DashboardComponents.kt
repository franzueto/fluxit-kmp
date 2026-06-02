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

/**
 * Drives the undo snackbar (plan/07 §3): the deleted list's [listName] and the
 * window [progress] (1f → 0f over the 5s countdown). Held by [DashboardRoute] and
 * passed into the stateless [DashboardScreen] so the snackbar stays
 * snapshot-renderable.
 */
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

/**
 * Loading placeholder (plan/07 §3): three muted rows standing in for content
 * while the first feed emission is in flight. A richer shimmer is deferred — the
 * DS exposes no skeleton primitive yet, and the literal-ban forbids raw sizing in
 * feature code, so this reuses [FluxItDashboardListItem] with neutral content.
 */
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

/**
 * Row subtitle per plan/07 §3 (resolved priority §12): empty list → "No items
 * yet"; otherwise "{n} items · {metadata}" where metadata is a completion percent
 * when partially done, else a relative "Last updated …". ([ListSummary] carries
 * no explicit subtitle field, so that highest-priority branch is a no-op for v1.)
 */
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

private fun relativeTime(
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
