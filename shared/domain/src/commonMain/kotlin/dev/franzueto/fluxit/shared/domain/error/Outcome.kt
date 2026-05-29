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

/**
 * Transform the error channel without touching the success channel.
 * Use-case sites call `repo.create(draft).mapError { it.toDomain(entity = "List") }`
 * to lift a [DataError]-bearing repository result into a [DomainError]-bearing
 * use-case result (Phase 04 §6).
 */
public inline fun <T, E, F> Outcome<T, E>.mapError(transform: (E) -> F): Outcome<T, F> =
    when (this) {
        is Outcome.Ok -> this
        is Outcome.Err -> Outcome.Err(transform(error))
    }

/**
 * Collapse both channels into a single value. The standard "I have a result,
 * I want to pattern-match it inline and produce a single output" pattern.
 */
public inline fun <T, E, R> Outcome<T, E>.fold(
    onOk: (T) -> R,
    onErr: (E) -> R,
): R =
    when (this) {
        is Outcome.Ok -> onOk(value)
        is Outcome.Err -> onErr(error)
    }
