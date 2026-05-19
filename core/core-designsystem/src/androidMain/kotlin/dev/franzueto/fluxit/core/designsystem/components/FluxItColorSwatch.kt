package dev.franzueto.fluxit.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors

@Composable
@Suppress("ktlint:standard:function-naming")
public fun FluxItColorSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(48.dp)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    this.selected = selected
                    if (contentDescription != null) {
                        this.contentDescription = contentDescription
                    }
                },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (selected) {
                            Modifier.border(width = 3.dp, color = FluxItColors.textPrimary, shape = CircleShape)
                        } else {
                            Modifier
                        },
                    ),
        )
    }
}
