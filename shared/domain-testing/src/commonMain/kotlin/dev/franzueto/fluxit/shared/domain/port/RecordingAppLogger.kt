package dev.franzueto.fluxit.shared.domain.port

/**
 * In-memory [AppLogger] fake (plan/06 §2). Records every log call so tests can
 * assert on what was logged without standing up Kermit. Lives in
 * `:shared:domain-testing` `commonMain` (alongside [FakeClock] etc.) so it is
 * reusable from any module's test source set — `:platform:platform-logging`
 * (the KermitAppLogger mapping test), `:platform:platform-analytics`
 * (`LoggingAnalyticsSink` routes events here), and store tests that want to
 * assert on logging instead of injecting [AppLogger.NoOp].
 */
public class RecordingAppLogger : AppLogger {
    public data class Entry(
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
    )

    public enum class Level { DEBUG, INFO, WARN, ERROR }

    private val _entries = mutableListOf<Entry>()

    /** Every recorded log call, in order. */
    public val entries: List<Entry> get() = _entries.toList()

    override fun debug(
        tag: String,
        message: String,
    ) {
        _entries += Entry(Level.DEBUG, tag, message, null)
    }

    override fun info(
        tag: String,
        message: String,
    ) {
        _entries += Entry(Level.INFO, tag, message, null)
    }

    override fun warn(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        _entries += Entry(Level.WARN, tag, message, throwable)
    }

    override fun error(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        _entries += Entry(Level.ERROR, tag, message, throwable)
    }

    /** Clears recorded entries — handy between phases of a single test. */
    public fun clear(): Unit = _entries.clear()
}
