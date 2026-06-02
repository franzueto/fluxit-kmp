package dev.franzueto.fluxit.core.designsystem.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.franzueto.fluxit.core.designsystem.icons.Briefcase
import dev.franzueto.fluxit.core.designsystem.icons.Cart
import dev.franzueto.fluxit.core.designsystem.icons.Dumbbell
import dev.franzueto.fluxit.core.designsystem.icons.FluxItIcons
import dev.franzueto.fluxit.core.designsystem.icons.ForkKnife
import dev.franzueto.fluxit.core.designsystem.icons.Home
import dev.franzueto.fluxit.core.designsystem.icons.More
import dev.franzueto.fluxit.core.designsystem.icons.Plane
import dev.franzueto.fluxit.core.designsystem.icons.Star
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef

/**
 * The design system's resolution of the domain's list-identity enums into
 * concrete Compose values (ADR-005a / ADR-006c: domain owns the *refs*, the
 * design system maps them to `ImageVector`/`Color`). Phase 02 only exercised
 * these mappings inside the debug Theme Gallery; Phase 07 promotes them to
 * public DS API so `:features:feature-lists` can render dashboard rows without
 * a raw `Color(0x…)` or icon lookup of its own (Konsist literal-ban stays green).
 *
 * Both `when`s are exhaustive over the domain enums, so a new `FluxItIconRef` /
 * `ColorToken` value breaks the build here until a swatch/glyph is chosen.
 */
public fun FluxItIconRef.toImageVector(): ImageVector =
    when (this) {
        FluxItIconRef.CART -> FluxItIcons.Cart
        FluxItIconRef.HOME -> FluxItIcons.Home
        FluxItIconRef.BRIEFCASE -> FluxItIcons.Briefcase
        FluxItIconRef.PLANE -> FluxItIcons.Plane
        FluxItIconRef.FORK_KNIFE -> FluxItIcons.ForkKnife
        FluxItIconRef.DUMBBELL -> FluxItIcons.Dumbbell
        FluxItIconRef.STAR -> FluxItIcons.Star
        FluxItIconRef.MORE -> FluxItIcons.More
    }

public fun ColorToken.toColor(): Color =
    when (this) {
        ColorToken.PRIMARY_BLUE -> FluxItColors.primaryBlue
        ColorToken.ACCENT_ROSE -> FluxItColors.accentRose
        ColorToken.ACCENT_EMERALD -> FluxItColors.accentEmerald
        ColorToken.ACCENT_ORANGE -> FluxItColors.accentOrange
        ColorToken.ACCENT_INDIGO -> FluxItColors.accentIndigo
        ColorToken.ACCENT_SKY -> FluxItColors.accentSky
    }
