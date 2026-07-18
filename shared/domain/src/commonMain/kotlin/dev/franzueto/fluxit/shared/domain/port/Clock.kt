package dev.franzueto.fluxit.shared.domain.port

import kotlinx.datetime.Instant
import kotlinx.datetime.Clock as DatetimeClock

/**
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
