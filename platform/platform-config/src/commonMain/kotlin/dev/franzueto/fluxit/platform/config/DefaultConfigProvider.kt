package dev.franzueto.fluxit.platform.config

import dev.franzueto.fluxit.shared.domain.port.ConfigKey
import dev.franzueto.fluxit.shared.domain.port.ConfigProvider

public object DefaultConfigProvider : ConfigProvider {
    override fun <T> get(key: ConfigKey<T>): T = key.default
}
