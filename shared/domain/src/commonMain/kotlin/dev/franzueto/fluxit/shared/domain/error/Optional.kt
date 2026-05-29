package dev.franzueto.fluxit.shared.domain.error

/**
 * A use-case parameter-shape primitive that distinguishes "don't touch this
 * field" ([Unset]) from "set this field to a value, possibly `null`"
 * ([Set]) — without the `null`-vs-absent ambiguity a plain `T?` carries
 * (Phase 04 §6).
 *
 * Introduced by [dev.franzueto.fluxit.shared.domain.usecase.items.UpdateItemDetails],
 * the partial-update use case that reads the current entity, applies only
 * the fields the caller actually supplied, and emits a *complete*
 * full-replacement payload to the repository. It deliberately does **not**
 * live on the data-edge `ItemPatch` (see ADR §4 reconciliation / Slice 4):
 * the SQL UPDATE writes every column atomically, so the data contract stays
 * a full-replacement shape; the don't-touch-vs-clear nuance is a use-case
 * concern and lives here.
 *
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
