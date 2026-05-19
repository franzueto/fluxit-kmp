package dev.franzueto.fluxit.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography

@Composable
@Suppress("ktlint:standard:function-naming")
public fun FluxItSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    searchIcon: ImageVector,
    placeholder: String = "Search",
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(FluxItColors.surfaceSearch)
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(imageVector = searchIcon, contentDescription = null, tint = FluxItColors.textMuted)
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(text = placeholder, style = FluxItTypography.bodyMd, color = FluxItColors.textMuted)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = FluxItTypography.bodyMd.copy(color = FluxItColors.textPrimary),
                cursorBrush = SolidColor(FluxItColors.primaryBlue),
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = placeholder },
            )
        }
    }
}
