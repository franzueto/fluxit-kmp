package dev.franzueto.fluxit.shared.state.store

/**
 * The four-way load status used by feature stores for an async collection
 * (`plan/05_STATE_MANAGEMENT.md` §4). State is always sufficient to render the
 * screen without consuming an effect (ADR-014):
 *
 * - [Loading] — first load in flight, nothing to show yet.
 * - [Empty] — the load completed but there is nothing (distinct from [Loading]
 *   so the UI can show an empty-state illustration rather than a spinner).
 * - [Loaded] — data is present.
 * - [Error] — the load failed; [Error.message] is already user-grade
 *   (mapped via `DomainError.userMessage`).
 *
 * Plain `sealed interface` — SKIE projects it as a Swift enum for exhaustive
 * `switch`; the SKIE `@SealedInterface` annotation is applied in Slice 5 with
 * the iOS smoke.
 */
public sealed interface LoadState<out T> {
    public data object Loading : LoadState<Nothing>

    public data object Empty : LoadState<Nothing>

    public data class Loaded<T>(
        val value: T,
    ) : LoadState<T>

    public data class Error(
        val message: String,
    ) : LoadState<Nothing>
}

/** Map a freshly-read collection into [LoadState]: an empty list is [LoadState.Empty]. */
internal fun <T> List<T>.toLoadState(): LoadState<List<T>> = if (isEmpty()) LoadState.Empty else LoadState.Loaded(this)
