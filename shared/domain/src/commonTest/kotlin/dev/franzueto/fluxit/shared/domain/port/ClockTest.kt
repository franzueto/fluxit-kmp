package dev.franzueto.fluxit.shared.domain.port

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClockTest {
    @Test
    fun system_returns_a_real_instant_close_to_now() {
        val before =
            kotlinx.datetime.Clock.System
                .now()
        val sample = Clock.System.now()
        val after =
            kotlinx.datetime.Clock.System
                .now()
        assertTrue(sample >= before && sample <= after, "Clock.System.now() must be bracketed by direct calls")
    }

    @Test
    fun fun_interface_supports_lambda_construction() {
        val fixed = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val fake: Clock = Clock { fixed }
        assertEquals(fixed, fake.now())
        // Subsequent calls return the same fixed instant (no autoadvance).
        assertEquals(fixed, fake.now())
    }
}
