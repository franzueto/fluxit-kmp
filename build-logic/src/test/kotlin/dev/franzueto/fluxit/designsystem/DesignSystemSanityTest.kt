package dev.franzueto.fluxit.designsystem

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import dev.franzueto.fluxit.tokens.KotlinEmitter
import dev.franzueto.fluxit.tokens.SwiftEmitter
import dev.franzueto.fluxit.tokens.TokenParser
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import java.io.File
import kotlin.math.pow

// Phase 02 §12 sanity tests.
//
// - Konsist rule (row 3): no Color(0x…), .dp / .sp literals, raw Font(…)
//   outside core-designsystem.
// - Token parity (row 1): every FluxItColors.* on Compose has a Swift
//   counterpart on FluxItTokens.Colors.*, parsed from the *generated* files.
// - A11y contrast (row 4): text.muted vs. every shipping surface ≥ 4.5:1
//   (or marked decorative-only with an explicit override).

class DesignSystemSanityTest : FunSpec({

    // build-logic test cwd is <repo>/build-logic. ".." lands on the repo root.
    val repoRoot = File("..").canonicalFile
    val scope = { Konsist.scopeFromDirectory("..") }

    test("no design-token literals (Color(0x…), .dp, .sp, font.Font(…)) outside core-designsystem") {
        // Patterns the rule bans. Each pattern triggers on hand-authored
        // production source files outside core-designsystem. Generated files
        // (under /build/) and test files are excluded.
        val colorHexLiteral = Regex("""\bColor\(0x[0-9A-Fa-f]""")
        val dpLiteral = Regex("""\b\d+(\.\d+)?\.dp\b""")
        val spLiteral = Regex("""\b\d+(\.\d+)?\.sp\b""")
        val rawFontCtor = Regex("""\bandroidx\.compose\.ui\.text\.font\.Font\(""")

        scope()
            .files
            .filter { file ->
                "/build/" !in file.path &&
                    "/core/core-designsystem/" !in file.path &&
                    "/build-logic/" !in file.path &&
                    "/src/test/" !in file.path &&
                    "/src/commonTest/" !in file.path &&
                    "/src/androidTest/" !in file.path &&
                    "/src/androidUnitTest/" !in file.path &&
                    !file.name.endsWith("Test.kt") &&
                    !file.name.endsWith("Spec.kt")
            }
            .assertFalse(
                additionalMessage =
                    "Design-token literals must live only in core-designsystem. " +
                        "Use FluxItColors.* / FluxItSpacing.* / FluxItTypography.* instead.",
            ) { file ->
                val text = file.text
                colorHexLiteral.containsMatchIn(text) ||
                    dpLiteral.containsMatchIn(text) ||
                    spLiteral.containsMatchIn(text) ||
                    rawFontCtor.containsMatchIn(text)
            }
    }

    test("every FluxItColors entry on Compose has a Swift counterpart") {
        // Parse the source-of-truth tokens.json and re-emit both the Compose
        // and Swift outputs in memory via the same emitters the Gradle tasks
        // use. This keeps the parity check self-contained — it doesn't require
        // :core:core-designsystem:generateTokens to have run first, and works
        // on a fresh CI checkout where ios-app/Generated/ is gitignored.
        val tokensJson = File(repoRoot, "core/core-designsystem/tokens/tokens.json")

        withClue("tokens source-of-truth must exist") {
            check(tokensJson.exists()) { "missing $tokensJson" }
        }

        val doc = TokenParser.parse(tokensJson)
        val composeSource = KotlinEmitter.emit(doc)["FluxItColors.kt"]
            ?: error("KotlinEmitter did not produce FluxItColors.kt")
        val swiftSource = SwiftEmitter.emit(doc)["FluxItTokens.swift"]
            ?: error("SwiftEmitter did not produce FluxItTokens.swift")
        val composeNames =
            Regex("""public val (\w+): Color""")
                .findAll(composeSource)
                .map { it.groupValues[1] }
                .toSortedSet()
        val swiftNames =
            Regex("""public static let (\w+): Color""")
                .findAll(swiftSource)
                .map { it.groupValues[1] }
                .toSortedSet()

        val missingOnSwift = composeNames - swiftNames
        val missingOnCompose = swiftNames - composeNames
        withClue("FluxItColors entries present in Compose but not Swift") {
            missingOnSwift.shouldBeEmpty()
        }
        withClue("FluxItTokens.Colors entries present in Swift but not Compose") {
            missingOnCompose.shouldBeEmpty()
        }
    }

    // WCAG 2.1 relative luminance + contrast ratio.
    fun srgbChannelToLinear(c: Double): Double = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    fun relativeLuminance(rgb: Triple<Int, Int, Int>): Double {
        val r = srgbChannelToLinear(rgb.first / 255.0)
        val g = srgbChannelToLinear(rgb.second / 255.0)
        val b = srgbChannelToLinear(rgb.third / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    fun contrastRatio(fg: Triple<Int, Int, Int>, bg: Triple<Int, Int, Int>): Double {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        val (light, dark) = if (l1 > l2) l1 to l2 else l2 to l1
        return (light + 0.05) / (dark + 0.05)
    }

    test("text.muted on every shipping surface meets WCAG-AA (4.5:1)") {
        val textMuted = Triple(0x9D, 0xA8, 0xB9)
        // surfaceCardMuted is #1e263280 — 50% alpha over backgroundDark. Its
        // effective opaque color when composited over #101822 is approximately
        // #17 1F 2A (alpha-blend of #1E2632 @ 50% over #101822). We assert
        // contrast on the composited result; the SwiftUI/Compose runtimes
        // composite identically for opaque surfaces under it.
        val compositeOverDark = { rgb: Triple<Int, Int, Int>, alpha: Double ->
            val (br, bg, bb) = Triple(0x10, 0x18, 0x22)
            Triple(
                (rgb.first * alpha + br * (1 - alpha)).toInt(),
                (rgb.second * alpha + bg * (1 - alpha)).toInt(),
                (rgb.third * alpha + bb * (1 - alpha)).toInt(),
            )
        }
        val surfaces: List<Pair<String, Triple<Int, Int, Int>>> =
            listOf(
                "background.dark" to Triple(0x10, 0x18, 0x22),
                "surface.card" to Triple(0x1E, 0x26, 0x32),
                "surface.search" to Triple(0x1E, 0x26, 0x32),
                "surface.cardMuted (over dark)" to compositeOverDark(Triple(0x1E, 0x26, 0x32), 0.5),
            )
        // primary.blue is excluded — text.muted-on-primary.blue is decorative-only
        // (no real screen places muted text on the brand-blue surface; the
        // CTAs always use text.primary). Document via this exclusion list.
        surfaces.forEach { (name, surface) ->
            val ratio = contrastRatio(textMuted, surface)
            withClue("text.muted on $name = ${"%.2f".format(ratio)}:1 (need ≥ 4.5)") {
                ratio.shouldBeGreaterThanOrEqual(4.5)
            }
        }
    }
})
