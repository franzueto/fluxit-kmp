package com.fluxit.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.fluxit.domain.ListColor

object FluxColors {
    val Background = Color(0xFF101822)
    val Surface = Color(0xFF1E2632)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextMuted = Color(0xFF9DA8B9)
    val PrimaryBlue = Color(0xFF2B7CEE)
    val AccentOrange = Color(0xFFF97316)
    val AccentEmerald = Color(0xFF10B981)
    val AccentRose = Color(0xFFF43F5E)
    val AccentIndigo = Color(0xFF6366F1)
    val AccentSky = Color(0xFF38BDF8)
}

fun ListColor.toColor(): Color = when (this) {
    ListColor.PRIMARY_BLUE -> FluxColors.PrimaryBlue
    ListColor.ORANGE -> FluxColors.AccentOrange
    ListColor.EMERALD -> FluxColors.AccentEmerald
    ListColor.ROSE -> FluxColors.AccentRose
    ListColor.INDIGO -> FluxColors.AccentIndigo
    ListColor.SKY -> FluxColors.AccentSky
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

private val FluxColorScheme = darkColorScheme(
    primary = FluxColors.PrimaryBlue,
    onPrimary = FluxColors.TextPrimary,
    background = FluxColors.Background,
    onBackground = FluxColors.TextPrimary,
    surface = FluxColors.Background,
    onSurface = FluxColors.TextPrimary,
    surfaceVariant = FluxColors.Surface,
    onSurfaceVariant = FluxColors.TextMuted,
    surfaceContainer = FluxColors.Surface,
    surfaceContainerHigh = FluxColors.Surface,
    surfaceContainerHighest = FluxColors.Surface,
    secondaryContainer = FluxColors.Surface,
    error = FluxColors.AccentRose,
    outline = FluxColors.TextMuted,
)

@Composable
fun FluxItTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FluxColorScheme,
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
