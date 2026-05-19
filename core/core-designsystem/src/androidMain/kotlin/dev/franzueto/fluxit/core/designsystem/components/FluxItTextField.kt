package dev.franzueto.fluxit.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography

@Composable
@Suppress("ktlint:standard:function-naming")
public fun FluxItTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label.uppercase(),
            style = FluxItTypography.captionXs,
            color = FluxItColors.textMuted,
        )
        androidx.compose.foundation.layout.Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(FluxItColors.surfaceCard)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(text = placeholder, style = FluxItTypography.bodyMd, color = FluxItColors.textMuted)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = FluxItTypography.bodyMd.copy(color = FluxItColors.textPrimary),
                cursorBrush = SolidColor(FluxItColors.primaryBlue),
                singleLine = singleLine,
                minLines = minLines,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 24.dp),
            )
        }
    }
}
