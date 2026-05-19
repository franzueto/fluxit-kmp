package dev.franzueto.fluxit.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItElevation
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItShapes
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography

// ADR-005b: v1 is dark-only. No isSystemInDarkTheme() branch; no light
// ColorScheme constructed. v2 light-theme is additive (populate tokens.json
// `light` group, branch here on system setting or a user toggle).

public val LocalFluxItColors: androidx.compose.runtime.ProvidableCompositionLocal<FluxItColors> =
    staticCompositionLocalOf { FluxItColors }
public val LocalFluxItTypography: androidx.compose.runtime.ProvidableCompositionLocal<FluxItTypography> =
    staticCompositionLocalOf { FluxItTypography }
public val LocalFluxItShapes: androidx.compose.runtime.ProvidableCompositionLocal<FluxItShapes> =
    staticCompositionLocalOf { FluxItShapes }
public val LocalFluxItSpacing: androidx.compose.runtime.ProvidableCompositionLocal<FluxItSpacing> =
    staticCompositionLocalOf { FluxItSpacing }
public val LocalFluxItElevation: androidx.compose.runtime.ProvidableCompositionLocal<FluxItElevation> =
    staticCompositionLocalOf { FluxItElevation }

private val FluxItDarkColorScheme =
    darkColorScheme(
        primary = FluxItColors.primaryBlue,
        onPrimary = FluxItColors.textPrimary,
        background = FluxItColors.backgroundDark,
        onBackground = FluxItColors.textPrimary,
        surface = FluxItColors.surfaceCard,
        onSurface = FluxItColors.textPrimary,
        surfaceVariant = FluxItColors.surfaceCardMuted,
        onSurfaceVariant = FluxItColors.textMuted,
        outline = FluxItColors.dividerSubtle,
    )

private val FluxItMaterialTypography =
    Typography(
        displayLarge = FluxItTypography.displayLg,
        titleMedium = FluxItTypography.titleMd,
        bodyMedium = FluxItTypography.bodyMd,
        labelSmall = FluxItTypography.labelSm,
    )

@Composable
@Suppress("ktlint:standard:function-naming")
public fun FluxItTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalFluxItColors provides FluxItColors,
        LocalFluxItTypography provides FluxItTypography,
        LocalFluxItShapes provides FluxItShapes,
        LocalFluxItSpacing provides FluxItSpacing,
        LocalFluxItElevation provides FluxItElevation,
    ) {
        MaterialTheme(
            colorScheme = FluxItDarkColorScheme,
            typography = FluxItMaterialTypography,
            content = content,
        )
    }
}
