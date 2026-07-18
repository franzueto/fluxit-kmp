package dev.franzueto.fluxit.shared.domain.port

import kotlinx.datetime.Instant
import kotlin.time.Duration

/**
 * Not thread-safe. Tests that share a single FakeClock across
 * coroutines should serialise their `advanceBy` calls externally
 * — domain tests run on a single coroutine in practice so this is
 * acceptable.
 */
public class FakeClock(
    initial: Instant,
) : Clock {
    private var current: Instant = initial

    override fun now(): Instant = current

    public fun advanceBy(duration: Duration) {
        require(duration >= Duration.ZERO) { "FakeClock.advanceBy must be non-negative: $duration" }
        current = current.plus(duration)
    }

    /** Set the clock to an arbitrary instant. Use for "reset" scenarios in fixture setup. */
    public fun setTo(instant: Instant) {
        current = instant
    }
}
