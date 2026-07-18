package dev.franzueto.fluxit.shared.domain.model

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.ValidationError
import kotlin.jvm.JvmInline

/**
 * Construct via [Companion.of], which returns an [Outcome] carrying a
 * typed [ValidationError] on failure. The primary constructor is private
 * so an invalid value can never be smuggled in.
 */
@JvmInline
public value class TrimmedNonBlank private constructor(
    public val value: String,
) {
    public companion object {
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
