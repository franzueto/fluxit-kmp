package dev.franzueto.fluxit.shared.domain.port

/**
 * A product-analytics event (plan/06 §3). Each event exposes an already-flattened,
 * vendor-agnostic payload: a snake_case [name] and a [properties] map. **No
 * user-content ever** — only enum tokens / counts / booleans (no list names, item
 * titles, or photo bytes), per the §3 privacy rule.
 *
 * v1 ships a deliberately small seed taxonomy; the full sealed hierarchy +
 * `docs/ANALYTICS_EVENTS.md` (PII classification, retention) is Phase 16 §4 work,
 * grown in lockstep as features start emitting events. Adding an event is a new
 * subclass here — every sink consumes [name]/[properties] uniformly.
 */
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
 * Domain port for emitting [AnalyticsEvent]s (plan/06 §3; Phase 04 §5 deferred it
 * to its first consumer). The single seam features/stores fire analytics through.
 *
 * v1 production binding is `LoggingAnalyticsSink` in `:platform:platform-analytics`
 * (ADR-012a — events flow to [AppLogger] at debug; nothing leaves the device). A
 * vendor sink (Firebase) is a v2 binding swap, not a contract change. Tests inject
 * a recording fake.
 */
public fun interface AnalyticsSink {
    public fun track(event: AnalyticsEvent)
}
