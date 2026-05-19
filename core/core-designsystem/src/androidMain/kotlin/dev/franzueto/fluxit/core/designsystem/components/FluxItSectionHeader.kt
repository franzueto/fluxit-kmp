package dev.franzueto.fluxit.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography

@Composable
@Suppress("ktlint:standard:function-naming")
public fun FluxItSectionHeader(
    label: String,
    trailingActionLabel: String? = null,
    onTrailingAction: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label.uppercase(),
            style = FluxItTypography.captionXs,
            color = FluxItColors.textMuted,
        )
        if (trailingActionLabel != null) {
            TextButton(onClick = onTrailingAction) {
                Text(
                    text = trailingActionLabel,
                    style = FluxItTypography.labelSm,
                    color = FluxItColors.primaryBlue,
                )
            }
        }
    }
}
