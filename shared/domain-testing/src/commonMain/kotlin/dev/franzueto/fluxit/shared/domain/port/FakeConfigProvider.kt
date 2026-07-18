package dev.franzueto.fluxit.shared.domain.port

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
