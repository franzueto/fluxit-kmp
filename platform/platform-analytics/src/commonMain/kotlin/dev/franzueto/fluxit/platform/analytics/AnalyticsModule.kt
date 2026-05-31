package dev.franzueto.fluxit.platform.analytics

import dev.franzueto.fluxit.shared.domain.port.AnalyticsSink
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for `:platform:platform-analytics` (plan/06 §3, §8). Binds the v1
 * [AnalyticsSink] → [LoggingAnalyticsSink] over the [AppLogger][dev.franzueto.fluxit.shared.domain.port.AppLogger]
 * resolved from `loggingModule` (so analytics depends on logging being wired
 * first — plan/06 §8 ordering). Replaces nothing interim (no analytics binding
 * existed in Phase 05); added to the graph in Slice 6.
 */
public val analyticsModule: Module =
    module {
        single<AnalyticsSink> { LoggingAnalyticsSink(get()) }
    }
