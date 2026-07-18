package dev.franzueto.fluxit.shared.domain.port

public sealed class AnalyticsEvent {
    /** Snake_case event name (the vendor-agnostic identifier). */
    public abstract val name: String

    /** Flattened, content-free properties. Empty for parameterless events. */
    public open val properties: Map<String, Any?> get() = emptyMap()

    /** App process reached its first composable screen. */
    public data object AppStarted : AnalyticsEvent() {
        override val name: String get() = "app_started"
    }

    /** A list was created. Carries only its appearance tokens — never the name. */
    public data class ListCreated(
        val color: String,
        val icon: String,
    ) : AnalyticsEvent() {
        override val name: String get() = "list_created"
        override val properties: Map<String, Any?> get() = mapOf("color" to color, "icon" to icon)
    }
}

/**
 * v1 production binding is `LoggingAnalyticsSink` in `:platform:platform-analytics`
 * (ADR-012a — events flow to [AppLogger] at debug; nothing leaves the device). A
 * vendor sink (Firebase) is a v2 binding swap, not a contract change. Tests inject
 * a recording fake.
 */
public fun interface AnalyticsSink {
    public fun track(event: AnalyticsEvent)
}
