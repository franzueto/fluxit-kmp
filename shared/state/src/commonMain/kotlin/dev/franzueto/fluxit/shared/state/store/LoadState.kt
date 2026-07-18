package dev.franzueto.fluxit.shared.state.store

/**
 * - [Loading] — first load in flight, nothing to show yet.
 * - [Empty] — the load completed but there is nothing (distinct from [Loading]
 *   so the UI can show an empty-state illustration rather than a spinner).
 * - [Loaded] — data is present.
 * - [Error] — the load failed; [Error.message] is already user-grade
 *   (mapped via `DomainError.userMessage`).
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
