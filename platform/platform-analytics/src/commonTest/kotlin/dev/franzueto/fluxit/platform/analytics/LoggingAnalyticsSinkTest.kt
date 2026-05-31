package dev.franzueto.fluxit.platform.analytics

import dev.franzueto.fluxit.shared.domain.port.AnalyticsEvent
import dev.franzueto.fluxit.shared.domain.port.RecordingAppLogger
import kotlin.test.Test
import kotlin.test.assertEquals

class LoggingAnalyticsSinkTest {
    @Test
    fun routes_events_to_the_app_logger_at_debug_under_the_analytics_tag() {
        val logger = RecordingAppLogger()
        val sink = LoggingAnalyticsSink(logger)

        sink.track(AnalyticsEvent.AppStarted)
        sink.track(AnalyticsEvent.ListCreated(color = "PRIMARY_BLUE", icon = "CART"))

        assertEquals(2, logger.entries.size)
        assertEquals(RecordingAppLogger.Level.DEBUG, logger.entries[0].level)
        assertEquals("Analytics", logger.entries[0].tag)
        assertEquals("app_started {}", logger.entries[0].message)
        assertEquals("list_created {color=PRIMARY_BLUE, icon=CART}", logger.entries[1].message)
    }
}
