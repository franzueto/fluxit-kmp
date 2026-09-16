package com.fluxit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.fluxit.domain.ListColor

private object FluxAccentColors {
    val PrimaryBlue = Color(0xFF2B7CEE)
    val AccentOrange = Color(0xFFF97316)
    val AccentEmerald = Color(0xFF10B981)
    val AccentRose = Color(0xFFF43F5E)
    val AccentIndigo = Color(0xFF6366F1)
    val AccentSky = Color(0xFF38BDF8)
}

fun ListColor.toColor(): Color = when (this) {
    ListColor.PRIMARY_BLUE -> FluxAccentColors.PrimaryBlue
    ListColor.ORANGE -> FluxAccentColors.AccentOrange
    ListColor.EMERALD -> FluxAccentColors.AccentEmerald
    ListColor.ROSE -> FluxAccentColors.AccentRose
    ListColor.INDIGO -> FluxAccentColors.AccentIndigo
    ListColor.SKY -> FluxAccentColors.AccentSky
}

object FluxType {
    val DisplayLg = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
    val TitleMd = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    val BodyMd = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal)
    val LabelSm = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
    val CaptionXs = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium)
}

object FluxSpacing {
    val ContainerPadding = 16.dp
    val StackGap = 8.dp
    val SectionGap = 12.dp
    val RowGap = 4.dp
}

val FluxCardShape = RoundedCornerShape(12.dp)

private val DarkBackground = Color(0xFF101822)
private val DarkSurface = Color(0xFF1E2632)
private val DarkTextPrimary = Color(0xFFFFFFFF)
private val DarkTextMuted = Color(0xFF9DA8B9)

private val FluxDarkColorScheme = darkColorScheme(
    primary = FluxAccentColors.PrimaryBlue,
    onPrimary = DarkTextPrimary,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkBackground,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkTextMuted,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurface,
    surfaceContainerHighest = DarkSurface,
    secondaryContainer = DarkSurface,
    error = FluxAccentColors.AccentRose,
    onError = DarkTextPrimary,
    outline = DarkTextMuted,
)

private val LightBackground = Color(0xFFF5F7FA)
private val LightSurface = Color(0xFFFFFFFF)
private val LightTextPrimary = Color(0xFF101822)
private val LightTextMuted = Color(0xFF5F6B7A)

private val FluxLightColorScheme = lightColorScheme(
    primary = Color(0xFF1769C2),
    onPrimary = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightBackground,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurface,
    onSurfaceVariant = LightTextMuted,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightSurface,
    surfaceContainerHighest = LightSurface,
    secondaryContainer = LightSurface,
    error = Color(0xFFD92D4F),
    onError = Color.White,
    outline = LightTextMuted,
)

@Composable
fun FluxItTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) FluxDarkColorScheme else FluxLightColorScheme,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = FluxCardShape,
            large = RoundedCornerShape(16.dp),
        ),
        typography = Typography().run {
            copy(
                displaySmall = displaySmall.merge(FluxType.DisplayLg),
                titleMedium = titleMedium.merge(FluxType.TitleMd),
                bodyMedium = bodyMedium.merge(FluxType.BodyMd),
                labelSmall = labelSmall.merge(FluxType.LabelSm),
            )
        },
        content = content,
    )
}
