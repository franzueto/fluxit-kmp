package dev.franzueto.fluxit.shared.state.error

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.ValidationError
import dev.franzueto.fluxit.shared.domain.port.CaptureError
import dev.franzueto.fluxit.shared.domain.port.SchedulerError

/**
 * Maps a [DomainError] to a user-facing English message (`plan/05` §9, ADR-014).
 *
 * Lives in `:shared:state`, **not** in `:shared:domain`, so the domain layer
 * stays locale-neutral — copy is a presentation concern. These strings are the
 * v1 placeholder; a Phase 02-style i18n token lookup can replace the right-hand
 * sides in v2 without touching call sites.
 *
 * Stores pre-map errors into `State.Error` or an `Effect.ShowError(...)` using
 * this extension, so the SKIE-exposed store surface never leaks `Outcome`/
 * `DomainError` to Swift (§3).
 *
 * Note on the two `PermissionDenied` cases: the message tells the user what to
 * allow, but the *paired* "open settings" effect is the store's responsibility
 * (it knows which effect type it has) — this extension only produces copy.
 * `CaptureError.UserCancelled` is mapped for completeness, but stores typically
 * suppress it (no error banner on a deliberate cancel — see the PhotoCapture port KDoc).
 */
public val DomainError.userMessage: String
    get() =
        when (this) {
            is DomainError.Validation -> validationMessage(field, rule)
            is DomainError.NotFound -> "We couldn't find that item."
            is DomainError.Conflict -> message
            is DomainError.StorageFailure -> "Something went wrong saving your changes."
            is DomainError.SchedulerFailure ->
                when (reason) {
                    SchedulerError.PermissionDenied -> "Allow notifications to set reminders."
                    SchedulerError.SystemBusy -> "We couldn't set that reminder just now. Please try again."
                    is SchedulerError.Unknown -> "We couldn't set that reminder. Please try again."
                }
            is DomainError.CaptureFailure ->
                when (reason) {
                    CaptureError.PermissionDenied -> "Allow camera access to add photos."
                    CaptureError.UserCancelled -> "Photo capture was cancelled."
                    is CaptureError.Unknown -> "We couldn't add that photo. Please try again."
                }
        }

private fun validationMessage(
    field: String,
    rule: ValidationError,
): String =
    when (rule) {
        ValidationError.Empty -> "$field can't be empty."
        is ValidationError.TooLong -> "$field is too long (max ${rule.max})."
        ValidationError.InvalidFormat -> "$field isn't in a valid format."
        ValidationError.NotInFuture -> "$field must be in the future."
    }
