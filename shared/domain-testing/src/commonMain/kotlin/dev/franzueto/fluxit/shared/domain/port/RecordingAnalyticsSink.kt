package dev.franzueto.fluxit.shared.domain.port

public class RecordingAnalyticsSink : AnalyticsSink {
    private val _events = mutableListOf<AnalyticsEvent>()

    /** Every tracked event, in order. */
    public val events: List<AnalyticsEvent> get() = _events.toList()

    override fun track(event: AnalyticsEvent) {
        _events += event
    }

    public fun clear(): Unit = _events.clear()
}
