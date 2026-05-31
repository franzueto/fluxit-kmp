package dev.franzueto.fluxit.platform.logging

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import dev.franzueto.fluxit.shared.domain.port.AppLogger

/**
 * Production [AppLogger] actual (plan/06 §2): adapts the domain logging port
 * onto a Kermit [Logger]. The store/use-case layer keeps depending on the pure
 * [AppLogger] port; only this module knows about Kermit.
 *
 * The four port levels map onto Kermit severities: [AppLogger.debug] → [Severity.Debug],
 * [AppLogger.info] → [Severity.Info], [AppLogger.warn] → [Severity.Warn],
 * [AppLogger.error] → [Severity.Error]. The port's `tag` is forwarded as Kermit's
 * tag so log lines stay grouped per subsystem; the optional [Throwable] rides the
 * warn/error channels.
 *
 * The backing [Logger]'s minimum severity + [co.touchlab.kermit.LogWriter] list is
 * supplied by [loggingModule] over the expect/actual [platformLogWriters] — this
 * adapter stays writer-agnostic. Crashlytics wiring is deferred (plan/06 §13 open
 * question): when adopted it is an extra writer in `platformLogWriters`, no change
 * here.
 */
public class KermitAppLogger(
    private val logger: Logger,
) : AppLogger {
    override fun debug(
        tag: String,
        message: String,
    ): Unit = logger.d(tag = tag) { message }

    override fun info(
        tag: String,
        message: String,
    ): Unit = logger.i(tag = tag) { message }

    override fun warn(
        tag: String,
        message: String,
        throwable: Throwable?,
    ): Unit = logger.w(throwable = throwable, tag = tag) { message }

    override fun error(
        tag: String,
        message: String,
        throwable: Throwable?,
    ): Unit = logger.e(throwable = throwable, tag = tag) { message }
}
