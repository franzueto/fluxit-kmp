package dev.franzueto.fluxit.icons

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import java.io.File

class SvgPathParserTest : FunSpec({

    test("parses a real Material Symbols SVG (plus glyph)") {
        // Verbatim from core/core-designsystem/icons/plus.svg.
        val xml = """
            <svg xmlns="http://www.w3.org/2000/svg" height="24px" viewBox="0 -960 960 960" width="24px" fill="#1f1f1f"><path d="M440-440H200v-80h240v-240h80v240h240v80H520v240h-80v-240Z"/></svg>
        """.trimIndent()

        val icon = SvgPathParser.parse(name = "plus", xmlText = xml)

        icon.name shouldBe "plus"
        icon.viewBoxMinX shouldBe 0.0
        icon.viewBoxMinY shouldBe -960.0
        icon.viewBoxWidth shouldBe 960.0
        icon.viewBoxHeight shouldBe 960.0

        // The path: M (abs) + alternating H/V (abs) closing with Z.
        // M440-440 H200 v-80 h240 v-240 h80 v240 h240 v80 H520 v240 h-80 v-240 Z
        icon.commands shouldBe listOf(
            MoveTo(440.0, -440.0, relative = false),
            HorizontalLineTo(200.0, relative = false),
            VerticalLineTo(-80.0, relative = true),
            HorizontalLineTo(240.0, relative = true),
            VerticalLineTo(-240.0, relative = true),
            HorizontalLineTo(80.0, relative = true),
            VerticalLineTo(240.0, relative = true),
            HorizontalLineTo(240.0, relative = true),
            VerticalLineTo(80.0, relative = true),
            HorizontalLineTo(520.0, relative = false),
            VerticalLineTo(240.0, relative = true),
            HorizontalLineTo(-80.0, relative = true),
            VerticalLineTo(-240.0, relative = true),
            Close,
        )
    }

    test("MoveTo followed by extra coordinate pairs implies LineTo (SVG §9.3.2)") {
        // `M 10 20 30 40 50 60` = MoveTo(10,20) + LineTo(30,40) + LineTo(50,60)
        val xml = svgWith("M 10 20 30 40 50 60 Z")
        val icon = SvgPathParser.parse("implicit-lineto", xml)
        icon.commands shouldBe listOf(
            MoveTo(10.0, 20.0, relative = false),
            LineTo(30.0, 40.0, relative = false),
            LineTo(50.0, 60.0, relative = false),
            Close,
        )
    }

    test("lowercase moveto implies relative lineto continuation") {
        val xml = svgWith("m 10 20 30 40 z")
        val icon = SvgPathParser.parse("implicit-rel-lineto", xml)
        icon.commands shouldBe listOf(
            MoveTo(10.0, 20.0, relative = true),
            LineTo(30.0, 40.0, relative = true),
            Close,
        )
    }

    test("parses C, S, Q, T curves") {
        val xml = svgWith("M 0 0 C 1 2 3 4 5 6 S 7 8 9 10 Q 11 12 13 14 T 15 16 Z")
        val icon = SvgPathParser.parse("curves", xml)
        icon.commands shouldBe listOf(
            MoveTo(0.0, 0.0, relative = false),
            CurveTo(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, relative = false),
            ReflectiveCurveTo(7.0, 8.0, 9.0, 10.0, relative = false),
            QuadTo(11.0, 12.0, 13.0, 14.0, relative = false),
            ReflectiveQuadTo(15.0, 16.0, relative = false),
            Close,
        )
    }

    test("number tokenizer: signs as separators, leading decimals, scientific notation") {
        // `1-2` = `1,-2`; `.5.5` = `.5,.5`; `1e-3` is a single scientific-notation
        // number (0.001). Note: tokens still need an unambiguous boundary — we
        // separate the trailing `0` with whitespace so the lexer doesn't read
        // `1e-30` as a single number.
        val xml = svgWith("M1-2L.5.5L1e-3 0")
        val icon = SvgPathParser.parse("numbers", xml)
        icon.commands shouldBe listOf(
            MoveTo(1.0, -2.0, relative = false),
            LineTo(0.5, 0.5, relative = false),
            LineTo(1e-3, 0.0, relative = false),
        )
    }

    test("commas and whitespace as separators") {
        val xml = svgWith("M 1,2 L3 4,5,6")
        val icon = SvgPathParser.parse("separators", xml)
        icon.commands shouldBe listOf(
            MoveTo(1.0, 2.0, relative = false),
            LineTo(3.0, 4.0, relative = false),
            LineTo(5.0, 6.0, relative = false),
        )
    }

    test("rejects arc commands with actionable error") {
        val xml = svgWith("M 0 0 A 5 5 0 0 1 10 10")
        val ex = shouldThrow<IllegalStateException> {
            SvgPathParser.parse("with-arc", xml)
        }
        ex.message!! shouldContain "arc command `A` is unsupported"
        ex.message!! shouldContain "with-arc"
    }

    test("rejects <g> grouping element") {
        val xml = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <g><path d="M 0 0 L 10 10 Z"/></g>
            </svg>
        """.trimIndent()
        val ex = shouldThrow<IllegalStateException> {
            SvgPathParser.parse("with-g", xml)
        }
        ex.message!! shouldContain "<g> is unsupported"
    }

    test("rejects <rect> and other primitive shapes") {
        val xml = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <rect width="10" height="10"/>
            </svg>
        """.trimIndent()
        val ex = shouldThrow<IllegalStateException> {
            SvgPathParser.parse("with-rect", xml)
        }
        ex.message!! shouldContain "<rect> is unsupported"
    }

    test("rejects transform attribute") {
        val xml = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <path transform="translate(5,5)" d="M 0 0 L 1 1 Z"/>
            </svg>
        """.trimIndent()
        val ex = shouldThrow<IllegalStateException> {
            SvgPathParser.parse("with-transform", xml)
        }
        ex.message!! shouldContain "`transform` attribute"
    }

    test("rejects gradient fill reference") {
        val xml = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <path fill="url(#grad)" d="M 0 0 L 1 1 Z"/>
            </svg>
        """.trimIndent()
        val ex = shouldThrow<IllegalStateException> {
            SvgPathParser.parse("with-gradient", xml)
        }
        ex.message!! shouldContain "gradient/pattern fill"
    }

    test("rejects multiple <path> elements") {
        val xml = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <path d="M 0 0 L 1 1"/>
              <path d="M 2 2 L 3 3"/>
            </svg>
        """.trimIndent()
        val ex = shouldThrow<IllegalStateException> {
            SvgPathParser.parse("multi-path", xml)
        }
        ex.message!! shouldContain "2 <path> elements found"
    }

    test("rejects missing viewBox") {
        val xml = """
            <svg xmlns="http://www.w3.org/2000/svg">
              <path d="M 0 0 L 1 1"/>
            </svg>
        """.trimIndent()
        val ex = shouldThrow<IllegalStateException> {
            SvgPathParser.parse("no-viewbox", xml)
        }
        ex.message!! shouldContain "missing required `viewBox`"
    }

    test("rejects path-data starting with a number") {
        val xml = svgWith("10 20 L 30 40")
        val ex = shouldThrow<IllegalStateException> {
            SvgPathParser.parse("no-leading-move", xml)
        }
        ex.message!! shouldContain "starts with a number"
    }

    test("round-trips every real SVG in core/core-designsystem/icons/") {
        // Smoke-test against the actual shipped icon set. If a future Material
        // Symbols download contains a construct we don't yet handle, this
        // catches it before the emitter does. Working directory is `build-logic/`
        // (Gradle default) — same `..`-relative convention as the Konsist arch
        // tests in :build-logic.
        val iconsDir = File("../core/core-designsystem/icons")
        val svgs = iconsDir.listFiles { f -> f.extension == "svg" }?.toList().orEmpty()
        svgs.shouldNotBeEmpty()

        svgs.forEach { svg ->
            val icon = SvgPathParser.parse(svg)
            check(icon.commands.isNotEmpty()) { "Icon `${svg.name}` parsed to zero commands" }
            check(icon.viewBoxWidth > 0 && icon.viewBoxHeight > 0) {
                "Icon `${svg.name}` has degenerate viewBox"
            }
        }
    }

    test("tolerates and ignores hard-coded fill on root") {
        // Material Symbols downloads ship with fill="#1f1f1f" — must not error.
        val xml = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="#1f1f1f">
              <path d="M 0 0 L 1 1 Z"/>
            </svg>
        """.trimIndent()
        val icon = SvgPathParser.parse("with-fill", xml)
        icon.commands shouldHaveSize 3 // M + L + Z
    }
})

private fun svgWith(pathData: String): String = """
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 -960 960 960">
      <path d="$pathData"/>
    </svg>
""".trimIndent()
