package dev.franzueto.fluxit.shared.domain.port

/**
 * Domain port for structured logging (Phase 04 §5). Use cases and the state
 * layer depend on this seam rather than calling Kermit (or any platform logger)
 * directly, so the logging backend stays swappable and tests can run silent.
 *
 * Phase 05 is the first consumer: every MVI store gets an [AppLogger] injected
 * (see `plan/05_STATE_MANAGEMENT.md` §10 + ADR-014). The production binding —
 * a Kermit-backed actual living in `:platform:platform-logging` — lands with
 * Phase 06; until then [NoOp] is the only binding and tests inject it directly.
 *
 * The port is intentionally minimal: four levels, a `tag` for grouping, and an
 * optional [Throwable] on the failure levels. No MDC / structured-field map for
 * v1 — add it here if a real consumer needs it (analytics is a separate port,
 * `AnalyticsSink`, per Phase 04 §5 — do not overload this one).
 */
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
        /**
         * The default binding until Phase 06 ships the Kermit-backed actual.
         * Discards every message. Also the binding tests inject so store unit
         * tests stay silent and assert on state/effects, not log output.
         */
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
