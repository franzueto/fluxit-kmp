package dev.franzueto.fluxit.shared.domain.error

import dev.franzueto.fluxit.shared.domain.port.CaptureError
import dev.franzueto.fluxit.shared.domain.port.SchedulerError

/**
 * The unified failure sum every use case returns through
 * `Outcome<T, DomainError>` (Phase 04 §6). Distinct from [DataError]:
 * domain errors carry use-case-level meaning ("the list named X
 * doesn't exist") rather than storage-level meaning ("a row with
 * that id is gone"). [DataError.toDomain] is the single bridge
 * between the two; use cases call it via `result.mapError { ... }`
 * at the repository → use-case seam.
 *
 * Slice 6 ships the four variants the four shipped Phase 03 data
 * repositories produce today. Two anticipated variants —
 * `SchedulerFailure(reason: SchedulerError)` and
 * `CaptureFailure(reason: CaptureError)` — land with the
 * `ReminderScheduler` and `PhotoCapture` port slices respectively,
 * once the underlying typed sums exist.
 */
public sealed class DomainError {
    /**
     * Use-case-level input validation failed. [field] names the
     * offending input; [rule] is the closed validator-discipline
     * reason (see [ValidationError]).
     *
     * Storage-side constraint violations (FK, unique) come in via
     * [DataError.Validation] and map to [Conflict] — they're not
     * the same shape of failure.
     */
    public data class Validation(
        val field: String,
        val rule: ValidationError,
    ) : DomainError()

    /**
     * The entity didn't resolve. [entity] is a human-readable type
     * label ("List", "Item", "Reminder", "Photo") supplied by the
     * call site via `toDomain(entity = ...)`; [id] is the raw id
     * string.
     */
    public data class NotFound(
        val entity: String,
        val id: String,
    ) : DomainError()

    /**
     * A concurrency / constraint / state conflict. Covers FK
     * violations, unique-key violations, stale writes, and the
     * coarse "tried to operate on something that's gone or busy"
     * shape. [message] is for diagnostics; UI should treat this
     * as "try again or refresh" without parsing the string.
     */
    public data class Conflict(
        val message: String,
    ) : DomainError()

    /**
     * Underlying storage failure (driver IO, file system, truly
     * unknown cause). [cause] is preserved for logging /
     * Crashlytics; UI should treat this as "something's wrong,
     * try again later" without inspecting the throwable.
     */
    public data class StorageFailure(
        val cause: Throwable?,
    ) : DomainError()

    /**
     * An OS-level reminder-scheduling operation failed (Phase 04 §5/§7).
     * [reason] is the typed [SchedulerError] from the
     * [dev.franzueto.fluxit.shared.domain.port.ReminderScheduler] port — UI
     * pattern-matches it (e.g. `PermissionDenied` → prompt for permission
     * and retry). Distinct from [StorageFailure]: the row may have persisted
     * fine; it's the platform schedule that didn't arm.
     */
    public data class SchedulerFailure(
        val reason: SchedulerError,
    ) : DomainError()

    /**
     * A photo capture / library pick failed (Phase 04 §5/§7). [reason] is
     * the typed [CaptureError] from the
     * [dev.franzueto.fluxit.shared.domain.port.PhotoCapture] port — UI
     * pattern-matches it (`PermissionDenied` → prompt; `UserCancelled` →
     * abort quietly without an error banner).
     */
    public data class CaptureFailure(
        val reason: CaptureError,
    ) : DomainError()
}

/**
 * Bridge from the data layer's typed failure sum (Phase 03 §5) to
 * the use-case layer's typed failure sum (Phase 04 §6). Called via
 * `result.mapError { it.toDomain(entity = "List") }` at the
 * repository → use-case seam.
 *
 * @param entity human-readable entity label for `NotFound`s
 *   ("List", "Item", "Reminder", "Photo"). Defaults to `"unknown"`
 *   for call sites where the entity context isn't relevant (e.g.
 *   composite use cases that touch multiple entity types).
 */
public fun DataError.toDomain(entity: String = "unknown"): DomainError =
    when (this) {
        is DataError.NotFound -> DomainError.NotFound(entity = entity, id = id)
        is DataError.Conflict -> DomainError.Conflict(message = reason)
        is DataError.Storage -> DomainError.StorageFailure(cause = cause)
        is DataError.Validation ->
            // Storage-side constraint violation (FK / unique) — surfaces
            // as a domain Conflict, NOT a DomainError.Validation. Use-case
            // input validation lives at the use-case edge, runs before
            // the repository call, and produces DomainError.Validation
            // directly without ever traveling through DataError.
            DomainError.Conflict(
                message = "Validation failed on field '$field': $reason",
            )
        is DataError.Unknown -> DomainError.StorageFailure(cause = cause)
    }
