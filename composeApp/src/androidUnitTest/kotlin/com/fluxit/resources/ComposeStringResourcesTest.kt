package com.fluxit.resources

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compose resources only unescape `\n`, `\t`, `\uXXXX` and `\\`; Android escapes such as `\'`
 * and `\"` stay in the text and show as a visible backslash on every platform. Shared strings
 * use plain `'` and `"`. Runs on the JVM, which can read the source file (Android unit tests run
 * in the module directory).
 */
class ComposeStringResourcesTest {
    @Test
    fun sharedStringsUseOnlyEscapesComposeUnderstands() {
        val strings = File("src/commonMain/composeResources/values/strings.xml").readLines()
        val unsupported = strings.withIndex().flatMap { (index, line) ->
            ESCAPE.findAll(line).filter { it.value !in SUPPORTED && !UNICODE.matches(it.value) }
                .map { "line ${index + 1}: ${it.value}" }.toList()
        }
        assertEquals(emptyList(), unsupported, "Use plain characters instead of these escapes in composeResources strings")
    }

    private companion object {
        val ESCAPE = Regex("""\\(u[0-9A-Fa-f]{4}|.)""")
        val UNICODE = Regex("""\\u[0-9A-Fa-f]{4}""")
        val SUPPORTED = setOf("\\n", "\\t", "\\\\")
    }
}
