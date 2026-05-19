package dev.franzueto.fluxit.tokens

// Strongly-typed model of the subset of the W3C Design Tokens Community Group
// format that FluxIt uses. The parser (TokenParser) walks tokens.json and emits
// instances of these types; the emitters (KotlinEmitter / SwiftEmitter) consume
// them. Keeping the model platform-agnostic is what lets us add new emitters
// later without touching parsing.
//
// Visibility is `internal` — these types are an implementation detail of the
// token-generation pipeline, not a public build-logic API.

internal data class Rgba(val r: Int, val g: Int, val b: Int, val a: Int) {
    init {
        require(r in 0..255 && g in 0..255 && b in 0..255 && a in 0..255) {
            "Rgba components must be 0..255; got ($r, $g, $b, $a)"
        }
    }

    companion object {
        /** Parses `#RRGGBB` (alpha implicitly 255) or `#RRGGBBAA`. Case-insensitive. */
        fun fromHex(hex: String): Rgba {
            val stripped = hex.trim().removePrefix("#")
            return when (stripped.length) {
                6 -> Rgba(
                    stripped.substring(0, 2).toInt(16),
                    stripped.substring(2, 4).toInt(16),
                    stripped.substring(4, 6).toInt(16),
                    a = 255,
                )
                8 -> Rgba(
                    stripped.substring(0, 2).toInt(16),
                    stripped.substring(2, 4).toInt(16),
                    stripped.substring(4, 6).toInt(16),
                    stripped.substring(6, 8).toInt(16),
                )
                else -> error("Invalid hex color `$hex` — expected #RRGGBB or #RRGGBBAA")
            }
        }
    }
}

internal sealed interface Token {
    /** Full DTCG path, e.g. `["dark", "color", "surface", "card"]`. */
    val path: List<String>
    val description: String?
}

internal data class ColorToken(
    override val path: List<String>,
    val color: Rgba,
    override val description: String? = null,
) : Token

internal data class DimensionToken(
    override val path: List<String>,
    val px: Double,
    override val description: String? = null,
) : Token

internal data class FontFamilyToken(
    override val path: List<String>,
    val families: List<String>,
    override val description: String? = null,
) : Token

internal data class TypographyToken(
    override val path: List<String>,
    val fontFamily: FontFamilyToken,
    val fontWeight: Int,
    val fontSizePx: Double,
    /** Unitless line-height multiplier (e.g. 1.2 means 120% of fontSize). */
    val lineHeight: Double,
    /** Letter-spacing in em (e.g. -0.02). Null when unspecified. */
    val letterSpacingEm: Double? = null,
    override val description: String? = null,
) : Token

internal data class ShadowToken(
    override val path: List<String>,
    val color: Rgba,
    val offsetXPx: Double,
    val offsetYPx: Double,
    val blurPx: Double,
    val spreadPx: Double,
    override val description: String? = null,
) : Token

internal data class TokenDocument(
    val tokens: List<Token>,
) {
    val colors: List<ColorToken> get() = tokens.filterIsInstance<ColorToken>()
    val dimensions: List<DimensionToken> get() = tokens.filterIsInstance<DimensionToken>()
    val fontFamilies: List<FontFamilyToken> get() = tokens.filterIsInstance<FontFamilyToken>()
    val typography: List<TypographyToken> get() = tokens.filterIsInstance<TypographyToken>()
    val shadows: List<ShadowToken> get() = tokens.filterIsInstance<ShadowToken>()
}
