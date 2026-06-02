package dev.franzueto.fluxit.shared.state.di

import dev.franzueto.fluxit.shared.state.debug.SeedSampleData
import dev.franzueto.fluxit.shared.state.store.AccountStore
import dev.franzueto.fluxit.shared.state.store.CreateListStore
import dev.franzueto.fluxit.shared.state.store.ItemDetailStore
import dev.franzueto.fluxit.shared.state.store.ListDetailStore
import dev.franzueto.fluxit.shared.state.store.ListsDashboardStore
import dev.franzueto.fluxit.shared.state.store.RootStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings for the `:shared:state` MVI stores (ADR-015). [RootStore] is a
 * `single` — it owns the app-session scope and runs InitializeApp once. The
 * per-screen stores are `factory`s: each screen entry gets a fresh store over a
 * fresh `CoroutineScope`, cancelled when the screen leaves.
 *
 * The `factory<CoroutineScope>` lives here (it moved out of the deleted interim
 * `platformModule` in Slice 6): every store gets a fresh `SupervisorJob`-backed
 * scope so one store's failure can't cancel another's. A caller may instead pass
 * its own scope as a Koin parameter — the Android `DashboardViewModel` passes
 * `viewModelScope` so [ListsDashboardStore]'s feed/undo timers are cancelled when
 * the screen leaves (Phase 07 §4); the no-arg resolves (iOS, tests) keep the
 * fresh-scope fallback.
 *
 * [AccountStore]'s `version`/`flags` are interim literals (ADR-015) until a later
 * slice routes them through the `ConfigProvider` now bound by `configModule`.
 */
public val stateModule: Module =
    module {
        factory<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        single { RootStore(get(), get(), get()) }
        factory { params ->
            ListsDashboardStore(params.getOrNull<CoroutineScope>() ?: get(), get(), get(), get(), get(), get())
        }
        factory { ListDetailStore(get(), get(), get(), get(), get(), get(), get(), get()) }
        factory { CreateListStore(get(), get(), get(), get()) }
        factory { ItemDetailStore(get(), get(), get(), get(), get(), get(), get(), get()) }
        factory { params ->
            AccountStore(params.getOrNull<CoroutineScope>() ?: get(), get(), version = "0.0.0-interim", flags = emptyMap())
        }
        // Debug-only seed action (plan/07 §7); the use case is harmless in any
        // build — the *button* is stripped from release via source-set selection.
        factory { SeedSampleData(get(), get(), get()) }
    }
