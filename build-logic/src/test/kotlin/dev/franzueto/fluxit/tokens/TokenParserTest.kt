package dev.franzueto.fluxit.tokens

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class TokenParserTest : FunSpec({

    test("parses 6-digit and 8-digit hex colors with inherited group \$type") {
        val json = """
            {
              "dark": {
                "color": {
                  "${'$'}type": "color",
                  "background": { "dark":   { "${'$'}value": "#101822" } },
                  "primary":    { "shadow": { "${'$'}value": "#2b7cee66" } }
                }
              }
            }
        """.trimIndent()

        val doc = TokenParser.parse(json)

        doc.colors.size shouldBe 2
        val bg = doc.colors.single { it.path.last() == "dark" && "background" in it.path }
        bg.color shouldBe Rgba(0x10, 0x18, 0x22, 0xFF)

        val shadow = doc.colors.single { it.path.last() == "shadow" }
        shadow.color shouldBe Rgba(0x2B, 0x7C, 0xEE, 0x66)
    }

    test("parses dimension tokens with px suffix") {
        val json = """
            {
              "spacing": {
                "${'$'}type": "dimension",
                "containerPadding": { "${'$'}value": "16px" },
                "scale": {
                  "xs": { "${'$'}value": "4px" },
                  "3xl": { "${'$'}value": "48px" }
                }
              }
            }
        """.trimIndent()

        val doc = TokenParser.parse(json)
        doc.dimensions.map { it.path.joinToString(".") to it.px } shouldContainExactlyInAnyOrder listOf(
            "spacing.containerPadding" to 16.0,
            "spacing.scale.xs" to 4.0,
            "spacing.scale.3xl" to 48.0,
        )
    }

    test("parses typography composite and resolves fontFamily alias") {
        val json = """
            {
              "font": {
                "family": {
                  "inter": { "${'$'}type": "fontFamily", "${'$'}value": ["Inter", "system-ui"] }
                }
              },
              "dark": {
                "typography": {
                  "${'$'}type": "typography",
                  "displayLg": {
                    "${'$'}value": {
                      "fontFamily": "{font.family.inter}",
                      "fontWeight": 700,
                      "fontSize": "32px",
                      "lineHeight": 1.2,
                      "letterSpacing": "-0.02em"
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val doc = TokenParser.parse(json)
        val t = doc.typography.single()
        t.path shouldBe listOf("dark", "typography", "displayLg")
        t.fontFamily.families shouldBe listOf("Inter", "system-ui")
        t.fontWeight shouldBe 700
        t.fontSizePx shouldBe 32.0
        t.lineHeight shouldBe 1.2
        t.letterSpacingEm shouldBe -0.02
    }

    test("parses shadow composite token") {
        val json = """
            {
              "dark": {
                "elevation": {
                  "${'$'}type": "shadow",
                  "fab": {
                    "${'$'}value": {
                      "color": "#2b7cee66",
                      "offsetX": "0px",
                      "offsetY": "8px",
                      "blur": "24px",
                      "spread": "0px"
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val doc = TokenParser.parse(json)
        val s = doc.shadows.single()
        s.color shouldBe Rgba(0x2B, 0x7C, 0xEE, 0x66)
        s.offsetXPx shouldBe 0.0
        s.offsetYPx shouldBe 8.0
        s.blurPx shouldBe 24.0
        s.spreadPx shouldBe 0.0
    }

    test("\$description is captured when present") {
        val json = """
            {
              "dark": {
                "color": {
                  "${'$'}type": "color",
                  "primary": {
                    "blue": { "${'$'}value": "#2b7cee", "${'$'}description": "Primary CTA tint." }
                  }
                }
              }
            }
        """.trimIndent()

        TokenParser.parse(json).colors.single().description shouldBe "Primary CTA tint."
    }

    test("unresolved fontFamily alias raises a path-bearing error") {
        val json = """
            {
              "dark": {
                "typography": {
                  "${'$'}type": "typography",
                  "bodyMd": {
                    "${'$'}value": {
                      "fontFamily": "{font.family.missing}",
                      "fontWeight": 400,
                      "fontSize": "16px",
                      "lineHeight": 1.5
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val ex = shouldThrow<IllegalStateException> { TokenParser.parse(json) }
        ex.message!! shouldContain "{font.family.missing}"
        ex.message!! shouldContain "dark.typography.bodyMd"
    }

    test("empty theme groups (light) are skipped without error") {
        val json = """
            {
              "light": { "${'$'}description": "Reserved" },
              "dark":  { "color": { "${'$'}type": "color", "x": { "${'$'}value": "#000000" } } }
            }
        """.trimIndent()

        TokenParser.parse(json).colors.single().color shouldBe Rgba(0, 0, 0, 255)
    }

    test("Rgba.fromHex rejects malformed input") {
        shouldThrow<IllegalStateException> { Rgba.fromHex("#abc") }
        shouldThrow<IllegalStateException> { Rgba.fromHex("not-a-color") }
    }
})
