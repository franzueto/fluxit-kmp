package dev.franzueto.fluxit.platform.config

import dev.franzueto.fluxit.shared.domain.port.ConfigKey
import dev.franzueto.fluxit.shared.domain.port.ConfigProvider

/**
 * v1 [ConfigProvider] (plan/06 §4): every flag resolves to its [ConfigKey.default]
 * (the ADR-004 values). No per-flavor / remote overrides yet.
 *
 * **Diverged from the §4 sketch's `BuildConfigProvider(buildKonfig)`:** BuildKonfig
 * is a Gradle plugin not in the version catalog, and v1 needs no per-flavor flag
 * values (the two staged features ship off everywhere). Wiring BuildKonfig is a
 * focused follow-up that swaps this binding in `configModule` for a
 * `BuildKonfigConfigProvider` — the `ConfigProvider`/`ConfigKey` port surface and
 * every call site stay unchanged.
 */
public object DefaultConfigProvider : ConfigProvider {
    override fun <T> get(key: ConfigKey<T>): T = key.default
}
