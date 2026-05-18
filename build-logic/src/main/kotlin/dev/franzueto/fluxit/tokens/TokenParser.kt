package dev.franzueto.fluxit.tokens

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

// Parser for the subset of W3C Design Tokens Community Group JSON that FluxIt
// authors in core-designsystem/tokens/tokens.json.
//
// Supports:
//   - $type inheritance from a group down to descendant tokens (DTCG §3.5).
//   - The token primitive types we actually use: color, dimension, fontFamily,
//     typography, shadow.
//   - Alias references of the form "{font.family.inter}" for fontFamily values
//     inside typography composites (resolved in a second pass once all
//     FontFamilyTokens are known).
//   - Theme groups "light" / "dark" at the document root (both walked the same
//     way; "light" is intentionally empty in v1 — see ADR-005b).
//
// Out of scope (intentional, can be added when a future token needs it):
//   - $extensions blocks (we ignore them).
//   - Math/calc references like "{spacing.scale.md} * 2".
//   - Non-px / non-em units. Inputs must be either a bare number or end in "px"
//     (dimensions) or "em" (letterSpacing).
//
// Errors are thrown as IllegalStateException with the offending DTCG path so
// build failures point straight at the broken token.

internal object TokenParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(file: File): TokenDocument = parse(file.readText())

    fun parse(jsonText: String): TokenDocument {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val collected = mutableListOf<Token>()
        walk(root, path = emptyList(), inheritedType = null, out = collected)
        return TokenDocument(resolveAliases(collected))
    }

    private fun walk(
        obj: JsonObject,
        path: List<String>,
        inheritedType: String?,
        out: MutableList<Token>,
    ) {
        val groupType = obj["\$type"]?.jsonPrimitive?.contentOrNull ?: inheritedType
        for ((key, value) in obj) {
            if (key.startsWith("\$")) continue
            if (value !is JsonObject) continue

            val tokenValue = value["\$value"]
            if (tokenValue != null) {
                val tokenPath = path + key
                val tokenType = value["\$type"]?.jsonPrimitive?.contentOrNull
                    ?: groupType
                    ?: error("Token at `${tokenPath.joinToString(".")}` has no \$type and no inheritable group \$type")
                val desc = value["\$description"]?.jsonPrimitive?.contentOrNull
                out += buildToken(tokenPath, tokenType, tokenValue, desc)
            } else {
                walk(value, path + key, groupType, out)
            }
        }
    }

    private fun buildToken(
        path: List<String>,
        type: String,
        value: JsonElement,
        description: String?,
    ): Token = when (type) {
        "color" -> ColorToken(
            path = path,
            color = Rgba.fromHex(value.jsonPrimitive.content),
            description = description,
        )

        "dimension" -> DimensionToken(
            path = path,
            px = parsePxOrBareNumber(value.jsonPrimitive.content, path),
            description = description,
        )

        "fontFamily" -> FontFamilyToken(
            path = path,
            families = when (value) {
                is JsonArray -> value.jsonArray.map { it.jsonPrimitive.content }
                is JsonPrimitive -> listOf(value.content)
                else -> error("fontFamily at `${path.joinToString(".")}` must be a string or string array")
            },
            description = description,
        )

        "typography" -> {
            val obj = value.jsonObject
            TypographyToken(
                path = path,
                // Placeholder family — resolved in resolveAliases when we know
                // every FontFamilyToken's path. The unresolved placeholder
                // carries the alias target path in its `path` field.
                fontFamily = parseFontFamilyValue(
                    obj["fontFamily"] ?: error("typography `${path.joinToString(".")}` missing fontFamily"),
                ),
                fontWeight = obj["fontWeight"]?.jsonPrimitive?.int
                    ?: error("typography `${path.joinToString(".")}` missing fontWeight"),
                fontSizePx = parsePxOrBareNumber(
                    obj["fontSize"]?.jsonPrimitive?.content
                        ?: error("typography `${path.joinToString(".")}` missing fontSize"),
                    path,
                ),
                lineHeight = obj["lineHeight"]?.jsonPrimitive?.content?.toDouble()
                    ?: error("typography `${path.joinToString(".")}` missing lineHeight"),
                letterSpacingEm = obj["letterSpacing"]?.jsonPrimitive?.content?.let { parseEm(it, path) },
                description = description,
            )
        }

        "shadow" -> {
            val obj = value.jsonObject
            ShadowToken(
                path = path,
                color = Rgba.fromHex(obj["color"]?.jsonPrimitive?.content ?: error("shadow `${path.joinToString(".")}` missing color")),
                offsetXPx = parsePxOrBareNumber(obj["offsetX"]?.jsonPrimitive?.content ?: "0px", path),
                offsetYPx = parsePxOrBareNumber(obj["offsetY"]?.jsonPrimitive?.content ?: "0px", path),
                blurPx = parsePxOrBareNumber(obj["blur"]?.jsonPrimitive?.content ?: "0px", path),
                spreadPx = parsePxOrBareNumber(obj["spread"]?.jsonPrimitive?.content ?: "0px", path),
                description = description,
            )
        }

        else -> error("Unsupported token \$type=`$type` at `${path.joinToString(".")}`")
    }

    private fun parseFontFamilyValue(element: JsonElement): FontFamilyToken {
        val text = (element as? JsonPrimitive)?.content
        if (text != null && text.startsWith("{") && text.endsWith("}")) {
            // Alias — capture the referenced path. Resolved later.
            val refPath = text.removeSurrounding("{", "}").split(".")
            return FontFamilyToken(path = refPath, families = emptyList())
        }
        // Inline value (rare; we expect aliases) — treat as an unattached family.
        val families = when (element) {
            is JsonArray -> element.jsonArray.map { it.jsonPrimitive.content }
            is JsonPrimitive -> listOf(element.content)
            else -> error("fontFamily must be alias, string, or string array")
        }
        return FontFamilyToken(path = emptyList(), families = families)
    }

    private fun resolveAliases(tokens: List<Token>): List<Token> {
        val familyByPath = tokens.filterIsInstance<FontFamilyToken>().associateBy { it.path }
        return tokens.map { tok ->
            if (tok is TypographyToken && tok.fontFamily.families.isEmpty() && tok.fontFamily.path.isNotEmpty()) {
                val resolved = familyByPath[tok.fontFamily.path]
                    ?: error("Unresolved fontFamily alias `{${tok.fontFamily.path.joinToString(".")}}` referenced from `${tok.path.joinToString(".")}`")
                tok.copy(fontFamily = resolved)
            } else {
                tok
            }
        }
    }

    private fun parsePxOrBareNumber(raw: String, path: List<String>): Double {
        val trimmed = raw.trim()
        return when {
            trimmed.endsWith("px") -> trimmed.removeSuffix("px").toDoubleOrNull()
                ?: error("Invalid dimension `$raw` at `${path.joinToString(".")}`")
            else -> trimmed.toDoubleOrNull()
                ?: error("Invalid dimension `$raw` at `${path.joinToString(".")}` — expected `<number>px` or bare number")
        }
    }

    private fun parseEm(raw: String, path: List<String>): Double {
        val trimmed = raw.trim()
        require(trimmed.endsWith("em")) {
            "letterSpacing `$raw` at `${path.joinToString(".")}` must end in `em`"
        }
        return trimmed.removeSuffix("em").toDoubleOrNull()
            ?: error("Invalid letterSpacing `$raw` at `${path.joinToString(".")}`")
    }
}
