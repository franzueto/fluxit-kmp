package dev.franzueto.fluxit.shared.domain.port

/**
 * A typed configuration / feature-flag key (plan/06 §4). Each key carries its
 * compile-time-safe value type [T] and a [default] used when no override is
 * supplied. Reading flags through these keys (rather than raw strings or
 * `BuildConfig.*`) is what lets Konsist keep flag access funnelled through the
 * single [ConfigProvider] seam (plan/06 §4 Konsist rule).
 *
 * Defaults come from ADR-004: the two staged-feature flags ship **off** in v1;
 * the photo/reminder tuning values match the Phase 03/04 §12 resolutions
 * (re-encode JPEG q=0.85, max dimension 2048).
 */
public sealed class ConfigKey<T>(
    public val id: String,
    public val default: T,
) {
    /** Calendar tab staged off in v1 (ADR-004). */
    public data object CalendarEnabled : ConfigKey<Boolean>("calendar.enabled", false)

    /** Starred/favorites staged off in v1 (ADR-004). */
    public data object StarredEnabled : ConfigKey<Boolean>("starred.enabled", false)

    /**
     * Reminder editor (Phase 13) staged off until it ships; while false the
     * Create-List screen's Reminder Settings row renders disabled "Coming
     * soon" (plan/09 §8 kill-switch path).
     */
    public data object RemindersEditorEnabled : ConfigKey<Boolean>("reminders.editor_enabled", false)

    /** Upper bound on how far ahead a reminder may be scheduled. */
    public data object ReminderMaxFutureDays : ConfigKey<Int>("reminders.max_future_days", 365)

    /** JPEG quality used when re-encoding an ingested photo (§12 row 4). */
    public data object PhotoReencodeQuality : ConfigKey<Float>("photo.reencode_quality", 0.85f)

    /** Longest edge (px) a re-encoded photo is downscaled to (§12 row 4). */
    public data object PhotoMaxDimension : ConfigKey<Int>("photo.max_dimension", 2048)
}

/**
 * Domain port for reading compile-time / feature-flag configuration (plan/06 §4).
 * The single seam the rest of the app reads flags through — production binding is
 * a `DefaultConfigProvider` in `:platform:platform-config` (BuildKonfig-backed in
 * v2; static ADR-004 defaults in v1). Tests inject a map-backed fake.
 */
public interface ConfigProvider {
    public fun <T> get(key: ConfigKey<T>): T
}
