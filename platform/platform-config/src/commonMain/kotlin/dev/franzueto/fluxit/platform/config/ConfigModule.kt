package dev.franzueto.fluxit.platform.config

import dev.franzueto.fluxit.shared.domain.port.Clock
import dev.franzueto.fluxit.shared.domain.port.ConfigProvider
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for `:platform:platform-config` (plan/06 §4, §8). Binds the three
 * "platform-shaped capability" ports the rest of the graph reads through:
 *
 *  - [ConfigProvider] → [DefaultConfigProvider] (static ADR-004 flag defaults).
 *  - [Clock] → [Clock.System] (kotlinx.datetime wall clock).
 *  - [IdGenerator] → [IdGenerator.System] (core-utils UUID-v4 actual, ADR-006a).
 *
 * `Clock`/`IdGenerator` already have cheap companion-default bindings; binding
 * them here makes `:platform:platform-config` the single Koin home for these
 * capabilities (plan/06 §4) and replaces the interim `Clock.System` binding that
 * `:shared:state`'s `InterimPlatformModule` carried through Phase 05. Wired second
 * among the platform modules (plan/06 §8 ordering) — after logging, before the
 * heavier capability modules — since stores mint ids / read "now" via these ports.
 */
public val configModule: Module =
    module {
        single<ConfigProvider> { DefaultConfigProvider }
        single<Clock> { Clock.System }
        single<IdGenerator> { IdGenerator.System }
    }
