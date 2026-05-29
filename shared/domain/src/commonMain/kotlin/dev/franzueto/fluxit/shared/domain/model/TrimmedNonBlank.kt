package dev.franzueto.fluxit.shared.domain.model

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.ValidationError
import kotlin.jvm.JvmInline

/**
 * A string guaranteed to be non-blank after trimming, optionally bounded
 * by a maximum length (Phase 04 §2). Centralises the "name not empty,
 * not just spaces, not over a cap" rule used by `ListDraft.name`,
 * `ItemDraft.title`, etc., so each draft-validation site doesn't
 * re-implement it.
 *
 * Construct via [Companion.of], which returns an [Outcome] carrying a
 * typed [ValidationError] on failure. The primary constructor is private
 * so an invalid value can never be smuggled in.
 */
@JvmInline
public value class TrimmedNonBlank private constructor(
    public val value: String,
) {
    public companion object {
        /**
         * @param raw input from the user; whitespace is trimmed before
         *   the non-empty check.
         * @param maxLen optional upper bound on the trimmed length.
         *   `null` disables the cap (Slice 3 default — per-field caps
         *   land with the use-case slices that own them).
         */
        public fun of(
            raw: String,
            maxLen: Int? = null,
        ): Outcome<TrimmedNonBlank, ValidationError> {
            val trimmed = raw.trim()
            return when {
                trimmed.isEmpty() -> Outcome.Err(ValidationError.Empty)
                maxLen != null && trimmed.length > maxLen ->
                    Outcome.Err(ValidationError.TooLong(maxLen))
                else -> Outcome.Ok(TrimmedNonBlank(trimmed))
            }
        }
    }
}
