package dev.franzueto.fluxit.platform.analytics

import dev.franzueto.fluxit.shared.domain.port.AnalyticsEvent
import dev.franzueto.fluxit.shared.domain.port.AnalyticsSink
import dev.franzueto.fluxit.shared.domain.port.AppLogger

/**
 * Routing through [AppLogger] (rather than Kermit directly) keeps this module off
 * any logging backend and lets tests assert via `RecordingAppLogger`.
 */
public class LoggingAnalyticsSink(
    private val logger: AppLogger,
) : AnalyticsSink {
    override fun track(event: AnalyticsEvent) {
        logger.debug(TAG, "${event.name} ${event.properties}")
    }

    private companion object {
        const val TAG = "Analytics"
    }
}
