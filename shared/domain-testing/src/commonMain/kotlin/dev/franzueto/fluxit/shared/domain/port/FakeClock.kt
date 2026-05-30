package dev.franzueto.fluxit.shared.domain.port

import kotlinx.datetime.Instant
import kotlin.time.Duration

/**
 * Reusable test fixture for the [Clock] port (Phase 04 §11 fakes
 * inventory). Constructed with an initial [Instant]; advances via
 * [advanceBy]. `now()` returns the current state, never auto-advances
 * — explicit control is the point.
 *
 * Lands here in Slice 8 because `RecurrenceCalculator`'s tests are
 * the first place that needs time-advance semantics; future use-case
 * tests (Phase 04 §7) and the data layer's clock-injection tests
 * will lean on the same fixture.
 *
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
