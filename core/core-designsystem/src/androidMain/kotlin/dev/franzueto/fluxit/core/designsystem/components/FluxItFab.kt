package dev.franzueto.fluxit.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors

@Composable
@Suppress("ktlint:standard:function-naming")
public fun FluxItFab(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(64.dp)
                .shadow(
                    elevation = 16.dp,
                    shape = CircleShape,
                    ambientColor = FluxItColors.primaryBlueShadow,
                    spotColor = FluxItColors.primaryBlueShadow,
                ).clip(CircleShape)
                .background(FluxItColors.primaryBlue)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = FluxItColors.textPrimary)
    }
}
