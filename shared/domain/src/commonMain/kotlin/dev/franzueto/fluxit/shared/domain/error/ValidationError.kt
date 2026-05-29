package dev.franzueto.fluxit.shared.domain.error

/**
 * Why a piece of input was rejected by a domain-layer validator
 * (Phase 04 §6). Carried by [DataError.Validation] at the domain edge
 * and by `Outcome.Err` from value-class factories like
 * [dev.franzueto.fluxit.shared.domain.model.TrimmedNonBlank.Companion.of].
 *
 * Seeded with the variants Slice 3 needs to ship `TrimmedNonBlank`.
 * Additional variants (`OutOfRange`, etc.) land in the use-case slices
 * that introduce them — keep the sum closed but grow it only when a
 * real validator needs the new shape.
 */
public sealed class ValidationError {
    /** Input was empty (or only whitespace, post-trim). */
    public data object Empty : ValidationError()

    /** Input exceeded the maximum length cap allowed for the field. */
    public data class TooLong(
        val max: Int,
    ) : ValidationError()

    /** Input didn't match the expected format (regex, structural shape). */
    public data object InvalidFormat : ValidationError()

    /**
     * A temporal input that had to be strictly in the future wasn't — e.g.
     * `ScheduleReminder`'s `firesAt > Clock.now()` guard (Phase 04 §7). The
     * speculative `OutOfRange(min, max)` from the §6 punch list models a
     * two-sided numeric range; a single-sided "must be after now" bound on
     * an `Instant` doesn't fit it, so this precise variant ships instead and
     * `OutOfRange` stays unbuilt until a real numeric-range validator needs it.
     */
    public data object NotInFuture : ValidationError()
}
