package dev.franzueto.fluxit.platform.logging

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import dev.franzueto.fluxit.shared.domain.port.AppLogger
import org.koin.core.module.Module
import org.koin.dsl.module

public val loggingModule: Module =
    module {
        single<Logger> {
            Logger(
                config = StaticConfig(minSeverity = Severity.Info, logWriterList = platformLogWriters()),
                tag = "FluxIt",
            )
        }
        single<AppLogger> { KermitAppLogger(get()) }
    }
