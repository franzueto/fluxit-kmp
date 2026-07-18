package dev.franzueto.fluxit.shared.state.di

import dev.franzueto.fluxit.platform.analytics.analyticsModule
import dev.franzueto.fluxit.platform.config.configModule
import dev.franzueto.fluxit.platform.logging.loggingModule
import dev.franzueto.fluxit.platform.photo.photoModule
import dev.franzueto.fluxit.platform.reminders.remindersModule
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.state.debug.SeedSampleData
import dev.franzueto.fluxit.shared.state.store.AccountStore
import dev.franzueto.fluxit.shared.state.store.CreateListStore
import dev.franzueto.fluxit.shared.state.store.ItemDetailStore
import dev.franzueto.fluxit.shared.state.store.ListDetailStore
import dev.franzueto.fluxit.shared.state.store.ListsDashboardStore
import dev.franzueto.fluxit.shared.state.store.RootStore
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.mp.KoinPlatform

/**
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
 *
 * [appDeclaration] runs inside the `startKoin { }` block before the modules are
 * registered, so a platform can install Koin extensions that other modules then
 * depend on — Android passes `{ androidContext(app) }` here, which the real
 * `remindersModule()` / `photoModule()` Android actuals resolve via
 * `androidContext()`. iOS and the JVM graph test need nothing extra, so it
 * defaults to a no-op.
 */
public fun initKoin(
    extra: List<Module> = emptyList(),
    appDeclaration: KoinAppDeclaration = {},
): KoinApplication =
    startKoin {
        appDeclaration()
        modules(appModules() + extra)
    }

public fun resolveRootStore(): RootStore = KoinPlatform.getKoin().get()

public fun resolveListsDashboardStore(): ListsDashboardStore = KoinPlatform.getKoin().get()

public fun resolveListDetailStore(): ListDetailStore = KoinPlatform.getKoin().get()

public fun resolveCreateListStore(): CreateListStore = KoinPlatform.getKoin().get()

public fun resolveCreateListStore(editingId: String): CreateListStore = KoinPlatform.getKoin().get { parametersOf(ListId(editingId)) }

public fun resolveItemDetailStore(): ItemDetailStore = KoinPlatform.getKoin().get()

public fun resolveAccountStore(): AccountStore = KoinPlatform.getKoin().get()

public fun resolveSeedSampleData(): SeedSampleData = KoinPlatform.getKoin().get()

public fun stopKoinApp(): Unit = stopKoin()
