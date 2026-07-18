package dev.franzueto.fluxit.shared.domain.port

public interface AppLogger {
    /** Verbose developer detail; compiled-in but typically filtered out in release. */
    public fun debug(
        tag: String,
        message: String,
    )

    /** Normal lifecycle / state-transition information (store intent + state delta). */
    public fun info(
        tag: String,
        message: String,
    )

    /** A recoverable problem — e.g. a use case returned a `DomainError` the store handled. */
    public fun warn(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )

    /** An unexpected failure that shouldn't happen in normal operation. */
    public fun error(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )

    public companion object {
        public val NoOp: AppLogger =
            object : AppLogger {
                override fun debug(
                    tag: String,
                    message: String,
                ) = Unit

                override fun info(
                    tag: String,
                    message: String,
                ) = Unit

                override fun warn(
                    tag: String,
                    message: String,
                    throwable: Throwable?,
                ) = Unit

                override fun error(
                    tag: String,
                    message: String,
                    throwable: Throwable?,
                ) = Unit
            }
    }
}
