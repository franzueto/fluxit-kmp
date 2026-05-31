package dev.franzueto.fluxit.shared.domain.port

/**
 * Map-backed [ConfigProvider] fake (plan/06 §4). Returns an override when one is
 * registered for a key, else the key's [ConfigKey.default]. Lives in
 * `:shared:domain-testing` commonMain so any module's tests can flip a flag (e.g.
 * a store test exercising the Calendar-enabled path) without standing up
 * `:platform:platform-config`.
 */
public class FakeConfigProvider(
    overrides: Map<ConfigKey<*>, Any?> = emptyMap(),
) : ConfigProvider {
    private val overrides: MutableMap<ConfigKey<*>, Any?> = overrides.toMutableMap()

    public fun <T> set(
        key: ConfigKey<T>,
        value: T,
    ) {
        overrides[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: ConfigKey<T>): T = if (overrides.containsKey(key)) overrides[key] as T else key.default
}
