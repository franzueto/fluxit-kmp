package dev.franzueto.fluxit.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography

@Composable
@Suppress("ktlint:standard:function-naming")
public fun FluxItPrimaryButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    FluxItColors.primaryBlue.copy(alpha = if (enabled) 1f else 0.4f),
                ).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            // FontWeight.Bold (700) is the WCAG 'large-text' threshold at 14pt+,
            // letting 4.02:1 white-on-primary.blue clear AA-large (3:1) — see §8.
            style = FluxItTypography.bodyMd.copy(fontWeight = FontWeight.Bold),
            color = FluxItColors.textPrimary,
        )
    }
}
