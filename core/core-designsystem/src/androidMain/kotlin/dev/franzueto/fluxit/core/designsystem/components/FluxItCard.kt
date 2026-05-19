package dev.franzueto.fluxit.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors

@Composable
@Suppress("ktlint:standard:function-naming")
public fun FluxItCard(
    modifier: Modifier = Modifier,
    resting: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (resting) FluxItColors.surfaceCardMuted else FluxItColors.surfaceCard,
                ).padding(16.dp),
        content = content,
    )
}
