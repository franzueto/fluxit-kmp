package dev.franzueto.fluxit.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography

/**
 * Swipe-to-delete row wrapper (plan/07 §12 — the resolved dashboard delete UX).
 * Wraps [content] in a Material3 [SwipeToDismissBox]; an end-to-start swipe
 * reveals a rose-tinted ([FluxItColors.accentRose]) delete affordance and, once
 * dismissed, invokes [onDelete]. Start-to-end is disabled (delete is the only
 * gesture).
 *
 * The store owns the actual removal + 5s undo window (optimistic delete), so
 * this primitive is pure presentation: it never animates the row back. The host
 * recomposes the list without the removed item, which is what visually "commits"
 * the swipe.
 *
 * Phase 02 §5 backfill (plan/07 §13): this primitive wasn't in the original
 * design-system checklist.
 */
@Composable
@Suppress("ktlint:standard:function-naming")
public fun FluxItSwipeRow(
    onDelete: () -> Unit,
    deleteIcon: ImageVector,
    modifier: Modifier = Modifier,
    deleteLabel: String = "Delete",
    content: @Composable () -> Unit,
) {
    val state =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { target ->
                if (target == SwipeToDismissBoxValue.EndToStart) {
                    onDelete()
                    true
                } else {
                    false
                }
            },
        )

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = { DeleteBackground(icon = deleteIcon, label = deleteLabel) },
        content = { content() },
    )
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun DeleteBackground(
    icon: ImageVector,
    label: String,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(FluxItColors.accentRose.copy(alpha = 0.2f))
                .padding(horizontal = FluxItSpacing.itemPaddingX),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = label, style = FluxItTypography.labelSm, color = FluxItColors.accentRose)
            Icon(imageVector = icon, contentDescription = null, tint = FluxItColors.accentRose)
        }
    }
}
