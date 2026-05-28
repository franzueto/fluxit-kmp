package dev.franzueto.fluxit.shared.domain.port

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class FakeClockTest {
    private val epoch = Instant.fromEpochSeconds(0)

    @Test
    fun now_returns_initial_until_advanced() {
        val clock = FakeClock(epoch)
        assertEquals(epoch, clock.now())
        assertEquals(epoch, clock.now())
    }

    @Test
    fun advance_by_moves_now_forward() {
        val clock = FakeClock(epoch)
        clock.advanceBy(60.seconds)
        assertEquals(Instant.fromEpochSeconds(60), clock.now())
        clock.advanceBy(1.hours)
        assertEquals(Instant.fromEpochSeconds(60 + 3600), clock.now())
    }

    @Test
    fun advance_by_zero_is_allowed_and_does_nothing() {
        val clock = FakeClock(epoch)
        clock.advanceBy(0.seconds)
        assertEquals(epoch, clock.now())
    }

    @Test
    fun advance_by_negative_throws() {
        val clock = FakeClock(epoch)
        assertFailsWith<IllegalArgumentException> { clock.advanceBy(-(1.seconds)) }
    }

    @Test
    fun set_to_jumps_clock_to_arbitrary_instant() {
        val clock = FakeClock(epoch)
        val future = Instant.fromEpochSeconds(1_000_000)
        clock.setTo(future)
        assertEquals(future, clock.now())
    }
}
