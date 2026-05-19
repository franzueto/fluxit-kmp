package dev.franzueto.fluxit.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography

// Variant 1 — Dashboard list-item: 56dp tinted icon container, title+subtitle,
// optional trash + chevron trailing slot.
@Composable
@Suppress("ktlint:standard:function-naming")
public fun FluxItDashboardListItem(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit = {},
    trashIcon: ImageVector? = null,
    onDelete: (() -> Unit)? = null,
    chevronIcon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(FluxItColors.surfaceCard)
                .clickable(onClick = onClick)
                .padding(horizontal = FluxItSpacing.itemPaddingX, vertical = FluxItSpacing.itemPaddingY),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(iconTint.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = FluxItTypography.titleMd, color = FluxItColors.textPrimary)
            if (subtitle != null) {
                Text(text = subtitle, style = FluxItTypography.labelSm, color = FluxItColors.textMuted)
            }
        }
        if (trashIcon != null && onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(imageVector = trashIcon, contentDescription = "Delete", tint = FluxItColors.textMuted)
            }
        }
        if (chevronIcon != null) {
            Icon(imageVector = chevronIcon, contentDescription = null, tint = FluxItColors.textMuted)
        }
    }
}

// Variant 2 — Detail to-buy item: radio leading, chevron trailing.
@Composable
@Suppress("ktlint:standard:function-naming")
public fun FluxItToBuyListItem(
    title: String,
    onToggle: () -> Unit,
    trailingIcon: ImageVector? = null,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(FluxItColors.surfaceCardMuted)
                .clickable(onClick = onClick)
                .padding(horizontal = FluxItSpacing.itemPaddingX, vertical = FluxItSpacing.itemPaddingY),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .border(width = 2.dp, color = FluxItColors.textMuted, shape = CircleShape)
                    .clickable(onClick = onToggle),
        )
        Text(
            text = title,
            style = FluxItTypography.bodyMd,
            color = FluxItColors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (trailingIcon != null) {
            Icon(imageVector = trailingIcon, contentDescription = null, tint = FluxItColors.textMuted)
        }
    }
}

// Variant 3 — Detail completed item: filled check leading, strikethrough title,
// trash trailing.
@Composable
@Suppress("ktlint:standard:function-naming")
public fun FluxItCompletedListItem(
    title: String,
    onToggle: () -> Unit,
    checkIcon: ImageVector,
    trashIcon: ImageVector,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(FluxItColors.surfaceCardMuted)
                .padding(horizontal = FluxItSpacing.itemPaddingX, vertical = FluxItSpacing.itemPaddingY),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(onClick = onToggle, modifier = Modifier.size(24.dp)) {
            Icon(imageVector = checkIcon, contentDescription = "Completed", tint = FluxItColors.primaryBlue)
        }
        Text(
            text = title,
            style = FluxItTypography.bodyMd.copy(textDecoration = TextDecoration.LineThrough),
            color = FluxItColors.textMuted,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
            Icon(imageVector = trashIcon, contentDescription = "Delete", tint = FluxItColors.textMuted)
        }
    }
}
