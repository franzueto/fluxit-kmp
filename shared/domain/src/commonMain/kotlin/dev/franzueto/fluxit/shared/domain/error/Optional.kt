package dev.franzueto.fluxit.shared.domain.error

/**
 * For a non-nullable target field use `Optional<T>` (`Set(v)` overwrites,
 * `Unset` leaves it); for a nullable target field use `Optional<T?>`
 * (`Set(null)` clears it, `Set(v)` overwrites, `Unset` leaves it).
 */
public sealed interface Optional<out T> {
    public data object Unset : Optional<Nothing>

    public data class Set<out T>(
        val value: T,
    ) : Optional<T>
}

/**
 * Resolve an [Optional] against the [current] value: [Optional.Set] wins,
 * [Optional.Unset] falls back to what's already there. The one-liner every
 * partial-update site uses to fold an intent into a complete payload.
 */
public fun <T> Optional<T>.orElse(current: T): T =
    when (this) {
        is Optional.Unset -> current
        is Optional.Set -> value
    }
