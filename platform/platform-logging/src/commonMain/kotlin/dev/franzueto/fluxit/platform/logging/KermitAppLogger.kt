package dev.franzueto.fluxit.platform.logging

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import dev.franzueto.fluxit.shared.domain.port.AppLogger

/**
 * The four port levels map onto Kermit severities: [AppLogger.debug] → [Severity.Debug],
 * [AppLogger.info] → [Severity.Info], [AppLogger.warn] → [Severity.Warn],
 * [AppLogger.error] → [Severity.Error]. The port's `tag` is forwarded as Kermit's
 * tag so log lines stay grouped per subsystem; the optional [Throwable] rides the
 * warn/error channels.
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
