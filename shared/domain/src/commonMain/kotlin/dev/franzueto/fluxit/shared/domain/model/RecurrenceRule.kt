package dev.franzueto.fluxit.shared.domain.model

import kotlinx.datetime.DayOfWeek
import kotlinx.serialization.Serializable

/**
 * Reminder recurrence policy. v1 scope locked by Phase 03 §12 row 1 (resolved
 * 2026-05-19) to the four canonical cases below. Custom RRULE deferred to v2;
 * the JSON shape is forward-compatible (new variants can be added as new
 * `@Serializable` subclasses without breaking on-disk reads of older rows).
 *
 * Stored serialised as JSON in `reminder.recurrence TEXT` (nullable column).
 * `None` and `null` are equivalent at the storage edge — the adapter reads
 * `null` and emits `None`, then writes `None` back as `null` (no JSON
 * payload for the trivial case). Other variants serialise to a single
 * JSON object with a `type` discriminator.
 */
@Serializable
public sealed class RecurrenceRule {
    @Serializable
    public data object None : RecurrenceRule()

    @Serializable
    public data object Daily : RecurrenceRule()

    @Serializable
    public data class Weekly(
        val daysOfWeek: Set<DayOfWeek>,
    ) : RecurrenceRule()

    @Serializable
    public data class Monthly(
        val dayOfMonth: Int,
    ) : RecurrenceRule()
}
