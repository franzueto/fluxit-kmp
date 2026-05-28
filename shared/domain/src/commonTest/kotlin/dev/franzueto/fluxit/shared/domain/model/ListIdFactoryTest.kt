package dev.franzueto.fluxit.shared.domain.model

import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ListIdFactoryTest {
    @Test
    fun new_delegates_to_injected_id_generator() {
        val fake = IdGenerator { "00000000-0000-4000-8000-000000000001" }
        val id = ListId.new(fake)
        assertEquals("00000000-0000-4000-8000-000000000001", id.value)
    }

    @Test
    fun new_calls_id_generator_each_invocation() {
        var counter = 0
        val seq = IdGenerator { "00000000-0000-4000-8000-${(++counter).toString().padStart(12, '0')}" }
        val a = ListId.new(seq)
        val b = ListId.new(seq)
        assertNotEquals(a, b)
        assertEquals("00000000-0000-4000-8000-000000000001", a.value)
        assertEquals("00000000-0000-4000-8000-000000000002", b.value)
    }
}
