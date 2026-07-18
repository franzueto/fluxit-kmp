package dev.franzueto.fluxit.platform.config

import dev.franzueto.fluxit.shared.domain.port.Clock
import dev.franzueto.fluxit.shared.domain.port.ConfigProvider
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 *  - [ConfigProvider] → [DefaultConfigProvider] (static ADR-004 flag defaults).
 *  - [Clock] → [Clock.System] (kotlinx.datetime wall clock).
 *  - [IdGenerator] → [IdGenerator.System] (core-utils UUID-v4 actual, ADR-006a).
 */
public val configModule: Module =
    module {
        single<ConfigProvider> { DefaultConfigProvider }
        single<Clock> { Clock.System }
        single<IdGenerator> { IdGenerator.System }
    }
