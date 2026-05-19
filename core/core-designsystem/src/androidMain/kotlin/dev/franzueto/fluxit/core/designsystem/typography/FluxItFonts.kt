@file:OptIn(ExperimentalTextApi::class)

package dev.franzueto.fluxit.core.designsystem.typography

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.franzueto.fluxit.core.designsystem.R

// Single Inter family backed by the bundled InterVariable.ttf (rsms/inter v4.1).
// Each Font() entry pins one named weight to the corresponding `wght` axis
// value on the variable font, so callers select weight the normal Compose way
// (FontWeight.Medium etc.) without knowing the font is variable.
//
// Resource lives at core-designsystem/src/androidMain/res/font/inter_variable.ttf.
// License next to it (OFL.txt).
private val InterFontFamily: FontFamily =
    FontFamily(
        Font(
            resId = R.font.inter_variable,
            weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(WEIGHT_REGULAR)),
        ),
        Font(
            resId = R.font.inter_variable,
            weight = FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(WEIGHT_MEDIUM)),
        ),
        Font(
            resId = R.font.inter_variable,
            weight = FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(WEIGHT_SEMIBOLD)),
        ),
        Font(
            resId = R.font.inter_variable,
            weight = FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(WEIGHT_BOLD)),
        ),
    )

public val FontFamily.Companion.Inter: FontFamily
    get() = InterFontFamily

private const val WEIGHT_REGULAR = 400
private const val WEIGHT_MEDIUM = 500
private const val WEIGHT_SEMIBOLD = 600
private const val WEIGHT_BOLD = 700
