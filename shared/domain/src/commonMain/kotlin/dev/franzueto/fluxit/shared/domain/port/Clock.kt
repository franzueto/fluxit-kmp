package dev.franzueto.fluxit.shared.domain.port

import kotlinx.datetime.Instant
import kotlinx.datetime.Clock as DatetimeClock

/**
 * Domain port for the wall clock (Phase 04 §5). Use cases that need
 * "now" depend on this seam rather than calling [DatetimeClock.System]
 * directly so tests can freeze / advance time deterministically.
 *
 * Production: bind [System] (delegates to [DatetimeClock.System.now]).
 * Tests: inject a fake that returns a controllable [Instant].
 */
public fun interface Clock {
    public fun now(): Instant

    public companion object {
        /** Production binding — defers to [kotlinx.datetime.Clock.System]. */
        public val System: Clock = Clock { DatetimeClock.System.now() }
    }
}
