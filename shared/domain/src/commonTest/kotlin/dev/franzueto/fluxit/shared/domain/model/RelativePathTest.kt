package dev.franzueto.fluxit.shared.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RelativePathTest {
    @Test
    fun accepts_typical_relative_path() {
        val path = RelativePath("photos/2026/05/abc.jpg")
        assertEquals("photos/2026/05/abc.jpg", path.raw)
    }

    @Test
    fun rejects_empty_string() {
        assertFailsWith<IllegalArgumentException> { RelativePath("") }
    }

    @Test
    fun rejects_whitespace_only_string() {
        assertFailsWith<IllegalArgumentException> { RelativePath("   ") }
    }
}
