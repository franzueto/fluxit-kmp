package dev.franzueto.fluxit.platform.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** A Kermit [LogWriter] that records what it was handed, for mapping assertions. */
private class RecordingWriter : LogWriter() {
    data class Line(
        val severity: Severity,
        val message: String,
        val tag: String,
        val throwable: Throwable?,
    )

    val lines = mutableListOf<Line>()

    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        lines += Line(severity, message, tag, throwable)
    }
}

class KermitAppLoggerTest {
    private fun loggerWith(writer: LogWriter) =
        // minSeverity = Verbose so every level reaches the writer under test.
        KermitAppLogger(Logger(StaticConfig(minSeverity = Severity.Verbose, logWriterList = listOf(writer)), tag = "FluxIt"))

    @Test
    fun maps_each_port_level_to_the_matching_kermit_severity_with_tag() {
        val writer = RecordingWriter()
        val logger = loggerWith(writer)

        logger.debug("Reminders", "d")
        logger.info("Reminders", "i")
        logger.warn("Reminders", "w")
        logger.error("Reminders", "e")

        assertEquals(
            listOf(
                Severity.Debug to "d",
                Severity.Info to "i",
                Severity.Warn to "w",
                Severity.Error to "e",
            ),
            writer.lines.map { it.severity to it.message },
        )
        assertEquals(List(4) { "Reminders" }, writer.lines.map { it.tag })
    }

    @Test
    fun forwards_throwable_on_warn_and_error_only() {
        val writer = RecordingWriter()
        val logger = loggerWith(writer)
        val boom = IllegalStateException("boom")

        logger.warn("T", "w", boom)
        logger.error("T", "e", boom)
        logger.info("T", "i")

        assertSame(boom, writer.lines[0].throwable)
        assertSame(boom, writer.lines[1].throwable)
        assertNull(writer.lines[2].throwable)
    }
}
