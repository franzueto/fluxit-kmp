package dev.franzueto.fluxit.shared.domain.error

public sealed class ValidationError {
    /** Input was empty (or only whitespace, post-trim). */
    public data object Empty : ValidationError()

    /** Input exceeded the maximum length cap allowed for the field. */
    public data class TooLong(
        val max: Int,
    ) : ValidationError()

    /** Input didn't match the expected format (regex, structural shape). */
    public data object InvalidFormat : ValidationError()

    public data object NotInFuture : ValidationError()
}
