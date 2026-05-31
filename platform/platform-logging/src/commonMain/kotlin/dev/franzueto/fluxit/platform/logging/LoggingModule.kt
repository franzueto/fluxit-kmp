package dev.franzueto.fluxit.platform.logging

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import dev.franzueto.fluxit.shared.domain.port.AppLogger
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for `:platform:platform-logging` (plan/06 §2, §8). Binds the
 * production [AppLogger] actual — a [KermitAppLogger] over a [Logger] configured
 * with the platform's [platformLogWriters]. Replaces the interim `AppLogger.NoOp`
 * binding that `:shared:state`'s `InterimPlatformModule` carries through Phase 05.
 *
 * Wired first among the platform modules (plan/06 §8 ordering) so the rest of the
 * graph can log during init. Minimum severity is [Severity.Info] in line with
 * plan/06 §2 (debug stays compiled-in but filtered out by default).
 */
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
