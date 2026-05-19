package dev.franzueto.fluxit.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors

@Composable
@Suppress("ktlint:standard:function-naming")
public fun FluxItIconChip(
    icon: ImageVector,
    tint: Color,
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier =
            modifier
                .size(80.dp)
                .clip(shape)
                .background(
                    (if (selected) FluxItColors.primaryBlue else tint).copy(alpha = 0.2f),
                ).then(
                    if (selected) {
                        Modifier.border(width = 2.dp, color = FluxItColors.primaryBlue, shape = shape)
                    } else {
                        Modifier
                    },
                ).clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    this.selected = selected
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) FluxItColors.primaryBlue else tint,
        )
    }
}
