package dev.franzueto.fluxit.core.utils

/**
 * Chains a [Result]-returning transform onto a successful [Result], short-circuiting on failure.
 *
 * `kotlin.Result` ships `map`, `mapCatching`, and `recover`, but no `flatMap` /
 * `andThen` for composing operations that themselves return `Result`. Without
 * this extension, callers either nest `fold` blocks or unwrap with `getOrThrow`.
 */
inline fun <T, R> Result<T>.andThen(transform: (T) -> Result<R>): Result<R> =
    fold(
        onSuccess = { transform(it) },
        onFailure = { Result.failure(it) },
    )
