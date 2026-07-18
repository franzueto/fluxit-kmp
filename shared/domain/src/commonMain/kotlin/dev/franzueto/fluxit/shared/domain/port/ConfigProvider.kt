package dev.franzueto.fluxit.shared.domain.port

public sealed class ConfigKey<T>(
    public val id: String,
    public val default: T,
) {
    /** Calendar tab staged off in v1 (ADR-004). */
    public data object CalendarEnabled : ConfigKey<Boolean>("calendar.enabled", false)

    /** Starred/favorites staged off in v1 (ADR-004). */
    public data object StarredEnabled : ConfigKey<Boolean>("starred.enabled", false)

    public data object RemindersEditorEnabled : ConfigKey<Boolean>("reminders.editor_enabled", false)

    /** Upper bound on how far ahead a reminder may be scheduled. */
    public data object ReminderMaxFutureDays : ConfigKey<Int>("reminders.max_future_days", 365)

    public data object PhotoReencodeQuality : ConfigKey<Float>("photo.reencode_quality", 0.85f)

    public data object PhotoMaxDimension : ConfigKey<Int>("photo.max_dimension", 2048)
}

public interface ConfigProvider {
    public fun <T> get(key: ConfigKey<T>): T
}
