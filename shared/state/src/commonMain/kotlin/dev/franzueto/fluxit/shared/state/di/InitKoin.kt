package dev.franzueto.fluxit.shared.state.di

import dev.franzueto.fluxit.shared.state.store.RootStore
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.mp.KoinPlatform

/**
 * The FluxIt composition root (ADR-015). The four layered modules resolve as a
 * single graph: stores → use cases → repositories → ports. The platform start
 * site supplies the missing `SqlDriver` (and, in Phase 06, the real platform
 * ports) through [extra].
 */
public fun appModules(): List<Module> = listOf(domainModule, dataModule, platformModule, stateModule)

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
