package dev.franzueto.fluxit.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdsTest {
    // ADR-006a canonical form: lowercase, hyphenated, version-4 nibble, RFC 4122 variant.
    private val uuidV4Canonical =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    @Test
    fun newId_matches_canonical_uuid_v4_form() {
        val id = newId()
        assertTrue(
            uuidV4Canonical.matches(id),
            "newId() returned `$id`, which does not match the ADR-006a canonical UUID v4 shape.",
        )
    }

    @Test
    fun newId_is_unique_across_calls() {
        val sample = List(1_000) { newId() }
        assertEquals(sample.size, sample.toSet().size, "newId() produced a collision in 1k samples.")
    }

    @Test
    fun idGenerator_System_delegates_to_newId() {
        val a = IdGenerator.System.newId()
        val b = IdGenerator.System.newId()
        assertTrue(uuidV4Canonical.matches(a))
        assertTrue(uuidV4Canonical.matches(b))
        assertNotEquals(a, b)
    }
}
