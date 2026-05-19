package dev.franzueto.fluxit.icons

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// Parser for the constrained SVG dialect that FluxIt accepts as icon source.
// Reads an `*.svg` file from core/core-designsystem/icons/ and produces an
// IconSource that emitters can translate to Compose materialIcon builders /
// iOS xcassets imagesets.
//
// Supports (per ADR-005a):
//   - Single root <svg> with a `viewBox` attribute.
//   - Exactly one <path> child with a `d` attribute.
//   - Path commands: M/m L/l H/h V/v C/c S/s Q/q T/t Z/z, with implicit-LineTo
//     coordinate-group continuation after M/m (per SVG spec §9.3.2).
//   - SVG number forms: integers, decimals, leading-decimal (`.5`), scientific
//     notation (`1e-3`), sign-as-separator (`1-2` = `1,-2`).
//
// Rejected at parse time (clear error messages):
//   - <g> grouping elements.
//   - <rect> / <circle> / <ellipse> / <polygon> / <polyline> / <line>.
//   - <linearGradient> / <radialGradient> and any `fill="url(#…)"` reference.
//   - `transform=` attributes anywhere in the document.
//   - Arc commands A/a.
//   - Multiple <path> elements.
//   - Missing viewBox.
//
// All `fill="…"` attributes on the <svg> or <path> are tolerated and ignored
// — the emitters tint at the call site (Compose `tint`, SwiftUI
// `.foregroundStyle()`), so the source's hard-coded `#1f1f1f` is irrelevant.

internal object SvgPathParser {

    /** Parses an icon file. The filename (without `.svg`) becomes [IconSource.name]. */
    fun parse(file: File): IconSource {
        require(file.extension.equals("svg", ignoreCase = true)) {
            "Expected `.svg` file, got `${file.name}`"
        }
        return parse(name = file.nameWithoutExtension, xmlText = file.readText())
    }

    /** Test-friendly overload that takes the icon name + raw XML text. */
    fun parse(name: String, xmlText: String): IconSource {
        val doc = DocumentBuilderFactory.newInstance().apply {
            // Don't fetch DTDs from W3C. Material Symbols downloads have no DTD,
            // but harden against future inputs that might reference one.
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isNamespaceAware = true
        }.newDocumentBuilder().parse(xmlText.byteInputStream())

        val svg = doc.documentElement
            ?: error("Icon `$name`: SVG has no root element")
        require(svg.localName == "svg") {
            "Icon `$name`: expected root <svg>, got <${svg.localName ?: svg.nodeName}>"
        }

        rejectTransformsAnywhere(svg, name)
        rejectUnsupportedElements(svg, name)

        val (minX, minY, width, height) = parseViewBox(svg, name)
        val pathElement = findSinglePath(svg, name)
        val d = pathElement.getAttribute("d").ifBlank {
            error("Icon `$name`: <path> has no `d` attribute")
        }
        val commands = parsePathData(d, name)

        return IconSource(
            name = name,
            viewBoxMinX = minX,
            viewBoxMinY = minY,
            viewBoxWidth = width,
            viewBoxHeight = height,
            commands = commands,
        )
    }

    // --- XML structural validation ---------------------------------------

    private fun rejectTransformsAnywhere(root: Element, name: String) {
        walk(root) { el ->
            if (el.hasAttribute("transform")) {
                error("Icon `$name`: `transform` attribute on <${el.localName ?: el.nodeName}> is unsupported — bake transforms into path coordinates before exporting")
            }
        }
    }

    private fun rejectUnsupportedElements(root: Element, name: String) {
        val unsupported = setOf(
            "g", "rect", "circle", "ellipse", "polygon", "polyline", "line",
            "linearGradient", "radialGradient", "pattern", "mask", "use", "symbol",
        )
        walk(root) { el ->
            val localName = el.localName ?: el.nodeName
            if (localName in unsupported) {
                error("Icon `$name`: <$localName> is unsupported — flatten to a single <path> before adding to the icon set")
            }
            // Reject `fill="url(#…)"` (gradient/pattern references).
            val fill = el.getAttribute("fill")
            if (fill.startsWith("url(")) {
                error("Icon `$name`: gradient/pattern fill `fill=\"$fill\"` on <$localName> is unsupported — icons must be monochrome")
            }
        }
    }

    private fun walk(root: Element, visit: (Element) -> Unit) {
        visit(root)
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                walk(node as Element, visit)
            }
        }
    }

    private fun parseViewBox(svg: Element, name: String): DoubleArray {
        val raw = svg.getAttribute("viewBox").ifBlank {
            error("Icon `$name`: <svg> is missing required `viewBox` attribute")
        }
        val parts = raw.trim().split(Regex("[ ,]+"))
        require(parts.size == 4) {
            "Icon `$name`: viewBox `$raw` must have 4 components (min-x min-y width height)"
        }
        val values = DoubleArray(4)
        for (i in 0..3) {
            values[i] = parts[i].toDoubleOrNull()
                ?: error("Icon `$name`: viewBox component `${parts[i]}` is not a number")
        }
        require(values[2] > 0 && values[3] > 0) {
            "Icon `$name`: viewBox width/height must be positive, got width=${values[2]} height=${values[3]}"
        }
        return values
    }

    private fun findSinglePath(svg: Element, name: String): Element {
        val paths = mutableListOf<Element>()
        walk(svg) { el ->
            if ((el.localName ?: el.nodeName) == "path") paths += el
        }
        return when (paths.size) {
            0 -> error("Icon `$name`: no <path> element found inside <svg>")
            1 -> paths.single()
            else -> error("Icon `$name`: ${paths.size} <path> elements found — icons must consist of a single path")
        }
    }

    // --- Path-data tokenization + command parsing ------------------------

    private fun parsePathData(d: String, name: String): List<PathCommand> {
        val commands = mutableListOf<PathCommand>()
        val tokens = tokenizePathData(d, name)

        var i = 0
        var lastCommand: Char? = null
        while (i < tokens.size) {
            val tok = tokens[i]
            val cmd = (tok as? PathToken.Letter)?.letter
                ?: run {
                    // Numbers without a preceding command repeat the previous
                    // command (per SVG path-data grammar). Only valid after a
                    // command has been seen.
                    lastCommand ?: error("Icon `$name`: path data starts with a number — expected an M/m command first")
                    // Fall through: parse args for `lastCommand`, advancing `i`.
                    null
                }

            if (cmd != null) {
                i++ // consume the letter
                lastCommand = cmd
            }
            val active = lastCommand!!

            i = parseOneCommand(active, tokens, i, commands, name)

            // After an M/m the implicit-continuation command is L/l (per SVG
            // §9.3.2): "If a moveto is followed by multiple pairs of coordinates,
            // the subsequent pairs are treated as implicit lineto commands."
            if (active == 'M') lastCommand = 'L'
            if (active == 'm') lastCommand = 'l'
        }

        if (commands.isEmpty()) {
            error("Icon `$name`: path data `$d` contained no commands")
        }
        return commands
    }

    /**
     * Reads exactly one command's worth of numeric arguments from [tokens]
     * starting at [start], appends a [PathCommand] to [out], and returns the
     * new cursor position.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun parseOneCommand(
        cmd: Char,
        tokens: List<PathToken>,
        start: Int,
        out: MutableList<PathCommand>,
        name: String,
    ): Int {
        val relative = cmd.isLowerCase()

        fun num(idx: Int): Double {
            val t = tokens.getOrNull(idx)
                ?: error("Icon `$name`: command `$cmd` truncated — expected more numbers")
            return (t as? PathToken.Number)?.value
                ?: error("Icon `$name`: command `$cmd` expected a number at position $idx, got command letter `${(t as PathToken.Letter).letter}`")
        }

        return when (cmd) {
            'M', 'm' -> {
                out += MoveTo(num(start), num(start + 1), relative)
                start + 2
            }
            'L', 'l' -> {
                out += LineTo(num(start), num(start + 1), relative)
                start + 2
            }
            'H', 'h' -> {
                out += HorizontalLineTo(num(start), relative)
                start + 1
            }
            'V', 'v' -> {
                out += VerticalLineTo(num(start), relative)
                start + 1
            }
            'C', 'c' -> {
                out += CurveTo(
                    x1 = num(start),
                    y1 = num(start + 1),
                    x2 = num(start + 2),
                    y2 = num(start + 3),
                    x = num(start + 4),
                    y = num(start + 5),
                    relative = relative,
                )
                start + 6
            }
            'S', 's' -> {
                out += ReflectiveCurveTo(
                    x2 = num(start),
                    y2 = num(start + 1),
                    x = num(start + 2),
                    y = num(start + 3),
                    relative = relative,
                )
                start + 4
            }
            'Q', 'q' -> {
                out += QuadTo(
                    x1 = num(start),
                    y1 = num(start + 1),
                    x = num(start + 2),
                    y = num(start + 3),
                    relative = relative,
                )
                start + 4
            }
            'T', 't' -> {
                out += ReflectiveQuadTo(num(start), num(start + 1), relative)
                start + 2
            }
            'Z', 'z' -> {
                out += Close
                start
            }
            'A', 'a' -> error("Icon `$name`: arc command `$cmd` is unsupported — approximate with cubic Bézier curves in your SVG editor before exporting")
            else -> error("Icon `$name`: unknown path command `$cmd`")
        }
    }

    // --- Path-data lexer -------------------------------------------------

    private sealed interface PathToken {
        data class Letter(val letter: Char) : PathToken
        data class Number(val value: Double) : PathToken
    }

    /**
     * Tokenizes a `d=` attribute into command letters and numbers.
     *
     * SVG number quirks handled:
     *  - Whitespace and commas are separators (and can be mixed/repeated).
     *  - A `+` or `-` mid-string starts a new number (so `1-2` = `1,-2`, and
     *    `1e-3-2` = `1e-3,-2`).
     *  - A `.` mid-number can also start a new number when the current number
     *    already has a decimal point (so `.5.5` = `.5,.5`).
     *  - Scientific notation: `e`/`E` followed by an optional sign and digits
     *    (e.g. `1.5e-3`).
     */
    private fun tokenizePathData(d: String, name: String): List<PathToken> {
        val out = mutableListOf<PathToken>()
        var i = 0
        while (i < d.length) {
            val c = d[i]
            when {
                c.isWhitespace() || c == ',' -> i++
                c.isLetter() -> {
                    out += PathToken.Letter(c)
                    i++
                }
                c == '-' || c == '+' || c == '.' || c.isDigit() -> {
                    val (value, next) = readNumber(d, i, name)
                    out += PathToken.Number(value)
                    i = next
                }
                else -> error("Icon `$name`: unexpected character `$c` in path data at index $i")
            }
        }
        return out
    }

    private fun readNumber(d: String, start: Int, name: String): Pair<Double, Int> {
        var i = start
        val sb = StringBuilder()

        // Optional leading sign.
        if (i < d.length && (d[i] == '+' || d[i] == '-')) {
            sb.append(d[i])
            i++
        }

        // Integer part (may be empty if the number is `.5`).
        var sawDigit = false
        while (i < d.length && d[i].isDigit()) {
            sb.append(d[i])
            i++
            sawDigit = true
        }

        // Fractional part.
        if (i < d.length && d[i] == '.') {
            sb.append('.')
            i++
            while (i < d.length && d[i].isDigit()) {
                sb.append(d[i])
                i++
                sawDigit = true
            }
        }

        require(sawDigit) {
            "Icon `$name`: malformed number starting at index $start (`${d.substring(start, minOf(start + 8, d.length))}…`)"
        }

        // Optional exponent.
        if (i < d.length && (d[i] == 'e' || d[i] == 'E')) {
            sb.append('e')
            i++
            if (i < d.length && (d[i] == '+' || d[i] == '-')) {
                sb.append(d[i])
                i++
            }
            val expStart = sb.length
            while (i < d.length && d[i].isDigit()) {
                sb.append(d[i])
                i++
            }
            require(sb.length > expStart) {
                "Icon `$name`: malformed scientific-notation exponent at index $i"
            }
        }

        val value = sb.toString().toDoubleOrNull()
            ?: error("Icon `$name`: could not parse number `$sb`")
        return value to i
    }
}
