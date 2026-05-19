package dev.franzueto.fluxit.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography

// Variant A — large display.lg title, optional trailing icon button. Lists Dashboard.
@Composable
@Suppress("ktlint:standard:function-naming")
public fun FluxItTopBarLarge(
    title: String,
    trailingIcon: ImageVector? = null,
    onTrailingClick: () -> Unit = {},
    trailingContentDescription: String? = null,
) {
    BarBackground {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluxItSpacing.containerPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = FluxItTypography.displayLg,
                color = FluxItColors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (trailingIcon != null) {
                IconButton(onClick = onTrailingClick) {
                    Icon(
                        imageVector = trailingIcon,
                        contentDescription = trailingContentDescription,
                        tint = FluxItColors.textPrimary,
                    )
                }
            }
        }
    }
}

// Variant B — centered title, leading back text-button, optional trailing icon. List Detail / Edit Item.
@Composable
@Suppress("ktlint:standard:function-naming")
public fun FluxItTopBarCentered(
    title: String,
    backLabel: String,
    onBack: () -> Unit,
    trailingIcon: ImageVector? = null,
    onTrailingClick: () -> Unit = {},
    trailingContentDescription: String? = null,
) {
    BarBackground {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = FluxItSpacing.containerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                style = FluxItTypography.titleMd,
                color = FluxItColors.textPrimary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackTextButton(label = backLabel, onClick = onBack)
                if (trailingIcon != null) {
                    IconButton(onClick = onTrailingClick) {
                        Icon(
                            imageVector = trailingIcon,
                            contentDescription = trailingContentDescription,
                            tint = FluxItColors.textPrimary,
                        )
                    }
                } else {
                    // Reserves symmetric space so the centered title stays centered.
                    Box(Modifier.size(48.dp))
                }
            }
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun BarBackground(content: @Composable () -> Unit) {
    // §7 finalizes the blur perf path; until then the bars use the §7-resolved
    // opaque fallback (surface.card @ 90%) — see plan/02_DESIGN_SYSTEM.md §7
    // Resolved Decisions (2026-05-11).
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(FluxItColors.surfaceCard.copy(alpha = 0.9f))
                .statusBarsPadding(),
    ) { content() }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun BackTextButton(
    label: String,
    onClick: () -> Unit,
) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text(
            text = "‹ $label",
            style = FluxItTypography.bodyMd,
            color = FluxItColors.primaryBlue,
        )
    }
}
