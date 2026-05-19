package dev.franzueto.fluxit.icons

// Strongly-typed model of a single SVG icon source file. The parser
// (SvgPathParser) reads an `*.svg` file from core/core-designsystem/icons/ and
// emits an IconSource; the emitters (KotlinIconEmitter / SwiftIconEmitter,
// added in follow-up commits) consume it. Keeping the model
// platform-agnostic is what lets us add new emitters later without touching
// parsing — same shape as the token pipeline (ADR-005, ADR-005a).
//
// Visibility is `internal` — these types are an implementation detail of the
// icon-generation pipeline, not a public build-logic API.

/**
 * One SVG path command. The hierarchy preserves the SVG command set 1:1
 * (M/L/H/V/C/S/Q/T/Z + their relative lowercase variants) so the Compose
 * emitter can map straight to `materialIcon { … }` builder calls
 * (`moveTo`/`moveToRelative`/`lineTo`/`lineToRelative`/…).
 *
 * Arcs (`A`/`a`) are intentionally absent — the parser rejects them at parse
 * time per ADR-005a, since Material Symbols glyphs at our chosen weight/grade
 * don't use them.
 */
internal sealed interface PathCommand {
    val relative: Boolean
}

internal data class MoveTo(
    val x: Double,
    val y: Double,
    override val relative: Boolean,
) : PathCommand

internal data class LineTo(
    val x: Double,
    val y: Double,
    override val relative: Boolean,
) : PathCommand

internal data class HorizontalLineTo(
    val x: Double,
    override val relative: Boolean,
) : PathCommand

internal data class VerticalLineTo(
    val y: Double,
    override val relative: Boolean,
) : PathCommand

internal data class CurveTo(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val x: Double,
    val y: Double,
    override val relative: Boolean,
) : PathCommand

internal data class ReflectiveCurveTo(
    val x2: Double,
    val y2: Double,
    val x: Double,
    val y: Double,
    override val relative: Boolean,
) : PathCommand

internal data class QuadTo(
    val x1: Double,
    val y1: Double,
    val x: Double,
    val y: Double,
    override val relative: Boolean,
) : PathCommand

internal data class ReflectiveQuadTo(
    val x: Double,
    val y: Double,
    override val relative: Boolean,
) : PathCommand

/**
 * `Z` and `z` are semantically identical in SVG (both close the current
 * subpath); we model `Close` as a singleton with `relative = false`.
 */
internal object Close : PathCommand {
    override val relative: Boolean = false
    override fun toString(): String = "Close"
}

/**
 * Parsed representation of a single icon source file.
 *
 * @property name kebab-case filename without `.svg` extension. Used by emitters
 *   to derive identifiers (`cart.svg` → Compose `FluxItIcons.Cart`, SwiftUI
 *   `FluxItTokens.Icons.cart`, xcassets imageset `ic-cart.imageset/`).
 * @property viewBoxMinX viewBox origin X (Material Symbols uses 0).
 * @property viewBoxMinY viewBox origin Y (Material Symbols uses -960).
 * @property viewBoxWidth viewBox width in viewport units (Material Symbols: 960).
 * @property viewBoxHeight viewBox height in viewport units (Material Symbols: 960).
 * @property commands ordered path commands extracted from the single `<path>`
 *   element's `d` attribute.
 */
internal data class IconSource(
    val name: String,
    val viewBoxMinX: Double,
    val viewBoxMinY: Double,
    val viewBoxWidth: Double,
    val viewBoxHeight: Double,
    val commands: List<PathCommand>,
)
