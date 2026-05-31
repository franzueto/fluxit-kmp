package dev.franzueto.fluxit.shared.domain.port

/**
 * In-memory [AnalyticsSink] fake (plan/06 §3). Records every tracked event so
 * store/integration tests can assert what was emitted. Lives in
 * `:shared:domain-testing` commonMain (next to the other port fakes) so it is
 * reusable from any module's tests — it is the only non-logging sink in v1.
 */
public class RecordingAnalyticsSink : AnalyticsSink {
    private val _events = mutableListOf<AnalyticsEvent>()

    /** Every tracked event, in order. */
    public val events: List<AnalyticsEvent> get() = _events.toList()

    override fun track(event: AnalyticsEvent) {
        _events += event
    }

    public fun clear(): Unit = _events.clear()
}
