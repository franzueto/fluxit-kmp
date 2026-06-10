@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.createlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import dev.franzueto.fluxit.core.designsystem.components.FluxItColorSwatch
import dev.franzueto.fluxit.core.designsystem.components.FluxItIconChip
import dev.franzueto.fluxit.core.designsystem.components.FluxItSectionHeader
import dev.franzueto.fluxit.core.designsystem.components.toColor
import dev.franzueto.fluxit.core.designsystem.components.toImageVector
import dev.franzueto.fluxit.core.designsystem.icons.Bell
import dev.franzueto.fluxit.core.designsystem.icons.ChevronRight
import dev.franzueto.fluxit.core.designsystem.icons.FluxItIcons
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItShapes
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.state.store.CreateListIntent
import dev.franzueto.fluxit.shared.state.store.CreateListState

/**
 * Icon chips a user can pick (plan/09 §5): the catalog minus the `MORE` glyph —
 * the "show more icons" affordance is dropped in v1 (the catalog has nothing
 * more to show), and `MORE` itself is the ⋯ chrome glyph, not a list identity.
 * v2: bring back the MORE chip when the icon set grows.
 */
internal fun pickableIcons(icons: List<FluxItIconRef>): List<FluxItIconRef> = icons.filterNot { it == FluxItIconRef.MORE }

/** §12: each chip/swatch is labelled with a human name, not just a visual. */
internal fun iconLabel(icon: FluxItIconRef): String =
    when (icon) {
        FluxItIconRef.CART -> "Cart"
        FluxItIconRef.HOME -> "Home"
        FluxItIconRef.BRIEFCASE -> "Briefcase"
        FluxItIconRef.PLANE -> "Plane"
        FluxItIconRef.FORK_KNIFE -> "Food"
        FluxItIconRef.DUMBBELL -> "Fitness"
        FluxItIconRef.STAR -> "Star"
        FluxItIconRef.MORE -> "More"
    }

internal fun colorLabel(color: ColorToken): String =
    when (color) {
        ColorToken.PRIMARY_BLUE -> "Blue"
        ColorToken.ACCENT_ROSE -> "Rose"
        ColorToken.ACCENT_EMERALD -> "Emerald"
        ColorToken.ACCENT_ORANGE -> "Orange"
        ColorToken.ACCENT_INDIGO -> "Indigo"
        ColorToken.ACCENT_SKY -> "Sky"
    }

/** §2: 4-column icon grid (chunked rows — the form already scrolls as a whole). */
@Composable
internal fun IconGridSection(
    state: CreateListState,
    onIntent: OnCreateListIntent,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleSm)) {
        FluxItSectionHeader(label = "CHOOSE ICON")
        pickableIcons(state.palette.icons).chunked(ICON_GRID_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleSm),
            ) {
                row.forEach { icon ->
                    val selected = icon == state.selectedIcon
                    FluxItIconChip(
                        icon = icon.toImageVector(),
                        tint = if (selected) state.selectedColor.toColor() else FluxItColors.textPrimary,
                        selected = selected,
                        onClick = { onIntent(CreateListIntent.IconSelected(icon)) },
                        contentDescription = iconLabel(icon),
                    )
                }
            }
        }
    }
}

/** §2: single horizontal row of the six swatches (§16 locked layout). */
@Composable
internal fun ColorRowSection(
    state: CreateListState,
    onIntent: OnCreateListIntent,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleSm)) {
        FluxItSectionHeader(label = "LIST COLOR")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleSm),
        ) {
            state.palette.colors.forEach { color ->
                FluxItColorSwatch(
                    color = color.toColor(),
                    selected = color == state.selectedColor,
                    onClick = { onIntent(CreateListIntent.ColorSelected(color)) },
                    contentDescription = colorLabel(color),
                )
            }
        }
    }
}

/**
 * §8 Reminder Settings row. Enabled only when `ConfigKey.RemindersEditorEnabled`
 * is on — off in v1 until Phase 13's editor exists, so the row renders muted
 * with a "Coming soon" subtitle (the §8 kill-switch rendering).
 */
@Composable
internal fun ReminderSection(
    state: CreateListState,
    onIntent: OnCreateListIntent,
) {
    val enabled = state.reminderEditorEnabled
    Column(verticalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleSm)) {
        FluxItSectionHeader(label = "REMINDER SETTINGS")
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(FluxItShapes.md)
                    .background(FluxItColors.surfaceCard)
                    .let { base ->
                        if (enabled) {
                            base.clickable { onIntent(CreateListIntent.ReminderSettingsClicked) }
                        } else {
                            base
                        }
                    }.padding(FluxItSpacing.scaleMd),
            horizontalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = FluxItIcons.Bell,
                contentDescription = null,
                tint = if (enabled) FluxItColors.textPrimary else FluxItColors.textMuted,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Reminder Settings",
                    style = FluxItTypography.bodyMd,
                    color = if (enabled) FluxItColors.textPrimary else FluxItColors.textMuted,
                )
                Text(
                    text = reminderSubtitle(state),
                    style = FluxItTypography.labelSm,
                    color = FluxItColors.textMuted,
                )
            }
            Icon(
                imageVector = FluxItIcons.ChevronRight,
                contentDescription = null,
                tint = FluxItColors.textMuted,
            )
        }
    }
}

/**
 * §8 subtitle: "Coming soon" while the editor flag is off; "None" until a
 * reminder is configured. The "{relative date} · {recurrence}" summary lands
 * with Phase 13's editor (it is unreachable while the flag ships off).
 */
internal fun reminderSubtitle(state: CreateListState): String =
    when {
        !state.reminderEditorEnabled -> "Coming soon"
        state.reminder == null -> "None"
        else -> "Scheduled"
    }

@Composable
internal fun InlineFieldError(message: String) {
    Text(
        text = message,
        style = FluxItTypography.labelSm,
        color = FluxItColors.accentRose,
    )
}

@Composable
internal fun ErrorBanner(message: String) {
    Text(
        text = message,
        style = FluxItTypography.labelSm,
        color = FluxItColors.accentRose,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val ICON_GRID_COLUMNS = 4
