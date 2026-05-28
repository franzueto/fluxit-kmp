package dev.franzueto.fluxit.shared.domain.error

/**
 * Typed-error result for repository operations (Phase 03 §5).
 *
 * Distinct from `kotlin.Result` so the failure channel carries a typed
 * [DataError] sum instead of a `Throwable`. Call sites pattern-match on the
 * concrete error variant rather than catching exceptions.
 */
public sealed interface Outcome<out T, out E> {
    public data class Ok<out T>(
        val value: T,
    ) : Outcome<T, Nothing>

    public data class Err<out E>(
        val error: E,
    ) : Outcome<Nothing, E>
}

public inline fun <T, E, R> Outcome<T, E>.map(transform: (T) -> R): Outcome<R, E> =
    when (this) {
        is Outcome.Ok -> Outcome.Ok(transform(value))
        is Outcome.Err -> this
    }

public inline fun <T, E, R> Outcome<T, E>.flatMap(transform: (T) -> Outcome<R, E>): Outcome<R, E> =
    when (this) {
        is Outcome.Ok -> transform(value)
        is Outcome.Err -> this
    }
