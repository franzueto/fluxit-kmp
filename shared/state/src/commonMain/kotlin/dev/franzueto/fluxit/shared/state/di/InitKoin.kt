package dev.franzueto.fluxit.shared.state.di

import dev.franzueto.fluxit.platform.analytics.analyticsModule
import dev.franzueto.fluxit.platform.config.configModule
import dev.franzueto.fluxit.platform.logging.loggingModule
import dev.franzueto.fluxit.platform.photo.photoModule
import dev.franzueto.fluxit.platform.reminders.remindersModule
import dev.franzueto.fluxit.shared.state.store.RootStore
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.mp.KoinPlatform

/**
 * The five real `:platform:*` Koin modules (Phase 06 Slice 6, plan §8), in the
 * §8 order: logging first (so everything downstream can log), then config (clock
 * + ids + flags), then the capability ports (analytics, reminders, photo). A
 * single aggregator keeps the Android and iOS start sites identical.
 *
 * `remindersModule()` / `photoModule()` are `expect`/`actual` — they resolve to
 * the Android (`androidContext()`-backed) or iOS actual at each start site.
 */
public fun fluxitPlatformModules(): List<Module> = listOf(loggingModule, configModule, analyticsModule, remindersModule(), photoModule())

/**
 * The FluxIt composition root (ADR-015). The layered modules resolve as a single
 * graph: stores → use cases → repositories → real platform ports. The platform
 * start site supplies the missing `SqlDriver` through [extra]; [platformModules]
 * defaults to the real [fluxitPlatformModules] but is injectable so the JVM graph
 * test can substitute fakes for the OS-context-bound capability ports.
 */
public fun appModules(platformModules: List<Module> = fluxitPlatformModules()): List<Module> =
    platformModules + domainModule + dataModule + stateModule

/**
 * Start Koin with the FluxIt graph plus any platform-supplied [extra] modules
 * (the `SqlDriver` binding is always required there). Android calls this from
 * `FluxItApp`; iOS from the `@main App`; tests pass an in-memory driver module.
 */
public fun initKoin(extra: List<Module> = emptyList()): KoinApplication = startKoin { modules(appModules() + extra) }

/**
 * Swift-callable resolver (ADR-015 Slice C). SKIE surfaces this top-level
 * function so the iOS app can pull the session-scoped [RootStore] after
 * [initKoin] without referencing Koin's Swift API directly.
 */
public fun resolveRootStore(): RootStore = KoinPlatform.getKoin().get()

/**
 * Tear down the Koin graph. Surfaced for the iOS runtime smoke (Slice C) so a
 * test can start a fresh graph per run; Phase 06's real composition roots own
 * Koin for the whole process and won't call this.
 */
public fun stopKoinApp(): Unit = stopKoin()
