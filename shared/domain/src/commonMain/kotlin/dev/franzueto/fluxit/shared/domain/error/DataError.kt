package dev.franzueto.fluxit.shared.domain.error

/**
 * Closed taxonomy of failures the data layer can surface to use cases
 * (Phase 03 §5). Every repository `suspend` write returns
 * [Outcome]`<T, DataError>`; readers (Flows) never emit errors — DB-level
 * failures propagate as cancellation exceptions, which is intentional.
 *
 * Variants are deliberately coarse: presentation concerns (e.g. "name too
 * long" vs. "name empty") live in the state layer's validators. The data
 * layer only knows that a constraint was violated and which field.
 */
public sealed class DataError {
    /** Row id resolved to nothing (or was already soft-deleted). */
    public data class NotFound(
        val id: String,
    ) : DataError()

    /** FK violation, unique constraint, or stale-write detected. */
    public data class Conflict(
        val reason: String,
    ) : DataError()

    /** Underlying storage failure (driver IO, file system, etc.). */
    public data class Storage(
        val cause: Throwable,
    ) : DataError()

    /** Input failed an invariant check before reaching the driver. */
    public data class Validation(
        val field: String,
        val reason: String,
    ) : DataError()

    /** Catch-all for unexpected failures; reserved for truly-unknown causes. */
    public data class Unknown(
        val cause: Throwable,
    ) : DataError()
}
