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

/**
 * Swift-callable resolver (ADR-015 Slice C). SKIE surfaces this top-level
 * function so the iOS app can pull the session-scoped [RootStore] after
 * [initKoin] without referencing Koin's Swift API directly.
 */
public fun resolveRootStore(): RootStore = KoinPlatform.getKoin().get()

/**
 * Swift-callable resolver for the Lists tab store. Unlike [RootStore] (a session
 * `single`), [ListsDashboardStore] is a Koin `factory`, so each call returns a
 * fresh store over a fresh `CoroutineScope` — the SwiftUI Lists screen resolves
 * one per appearance and lets it cancel when the view leaves (Phase 06 Slice 7).
 */
public fun resolveListsDashboardStore(): ListsDashboardStore = KoinPlatform.getKoin().get()

/**
 * Swift-callable resolver for the List Detail store (plan/08 §9). Like
 * [ListsDashboardStore] a Koin `factory` (fresh store + scope per appearance); the
 * SwiftUI `ListDetailView` resolves one per appearance and dispatches
 * `ListDetailIntent.Init(listId)` to bind it to a specific list. The optional
 * `CoroutineScope` factory param is unused from Swift — it resolves over a fresh
 * `SupervisorJob` scope (the iOS view's lifetime owns cancellation via the store's
 * own `scope`).
 */
public fun resolveListDetailStore(): ListDetailStore = KoinPlatform.getKoin().get()

/**
 * Swift-callable resolver for the Create-List store in **create mode** (plan/09
 * §10). Like [ListsDashboardStore] a Koin `factory` (fresh store + scope per
 * appearance); the SwiftUI `CreateListView` resolves one per presentation. The
 * optional `CoroutineScope`/`ListId` factory params are both omitted, so the
 * store builds over a fresh `SupervisorJob` scope with `editingId = null`.
 */
public fun resolveCreateListStore(): CreateListStore = KoinPlatform.getKoin().get()

/**
 * Swift-callable resolver for the Create-List store in **edit mode** (plan/09
 * §9). Builds the [ListId] from the route-arg string internally (cf. [listIdOf])
 * and passes it through `parametersOf` so the Koin factory's `getOrNull<ListId>()`
 * flips the store into edit mode — Swift can't supply the boxed value class via
 * the default-arg constructor directly.
 */
public fun resolveCreateListStore(editingId: String): CreateListStore = KoinPlatform.getKoin().get { parametersOf(ListId(editingId)) }

/**
 * Swift-callable resolver for the Edit-Item store (plan/10 §8). A Koin `factory`
 * (fresh store + scope per appearance); the SwiftUI `ItemDetailView` resolves one
 * per presentation. The target item id is supplied later via
 * `ItemDetailIntent.Init` (built from the route-arg string with [itemIdOf]), not at
 * resolution — so this takes no parameter.
 */
public fun resolveItemDetailStore(): ItemDetailStore = KoinPlatform.getKoin().get()

/**
 * Swift-callable resolver for the Account tab store. Like [ListsDashboardStore] a
 * Koin `factory` (a fresh store + scope per appearance); the iOS Account screen
 * resolves one per appearance. `version`/`flags` stay the interim literals bound
 * in `stateModule` until a later slice routes them through `ConfigProvider`.
 */
public fun resolveAccountStore(): AccountStore = KoinPlatform.getKoin().get()

/**
 * Swift-callable resolver for the debug-only [SeedSampleData] use case (plan/07
 * §7). The use case is harmless in any build — the iOS Account screen gates the
 * *button* behind a `#if DEBUG`, mirroring the Android source-set strip.
 */
public fun resolveSeedSampleData(): SeedSampleData = KoinPlatform.getKoin().get()

/**
 * Tear down the Koin graph. Surfaced for the iOS runtime smoke (Slice C) so a
 * test can start a fresh graph per run; Phase 06's real composition roots own
 * Koin for the whole process and won't call this.
 */
public fun stopKoinApp(): Unit = stopKoin()
