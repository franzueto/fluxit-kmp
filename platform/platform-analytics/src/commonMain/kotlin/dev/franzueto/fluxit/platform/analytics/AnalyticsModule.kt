package dev.franzueto.fluxit.platform.analytics

import dev.franzueto.fluxit.shared.domain.port.AnalyticsSink
import org.koin.core.module.Module
import org.koin.dsl.module

public val analyticsModule: Module =
    module {
        single<AnalyticsSink> { LoggingAnalyticsSink(get()) }
    }
