package dev.franzueto.fluxit.shared.state.di

import dev.franzueto.fluxit.shared.state.store.AccountStore
import dev.franzueto.fluxit.shared.state.store.CreateListStore
import dev.franzueto.fluxit.shared.state.store.ItemDetailStore
import dev.franzueto.fluxit.shared.state.store.ListDetailStore
import dev.franzueto.fluxit.shared.state.store.ListsDashboardStore
import dev.franzueto.fluxit.shared.state.store.RootStore
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings for the `:shared:state` MVI stores (ADR-015). [RootStore] is a
 * `single` — it owns the app-session scope and runs InitializeApp once. The
 * per-screen stores are `factory`s: each screen entry gets a fresh store over a
 * fresh `CoroutineScope` (the platform `factory<CoroutineScope>` in
 * [platformModule]), cancelled when the screen leaves.
 *
 * [AccountStore]'s `version`/`flags` are interim literals (ADR-015) until
 * `:platform:platform-config` supplies a real `ConfigProvider` in Phase 06.
 */
public val stateModule: Module =
    module {
        single { RootStore(get(), get(), get()) }
        factory { ListsDashboardStore(get(), get(), get(), get(), get(), get()) }
        factory { ListDetailStore(get(), get(), get(), get(), get(), get(), get(), get()) }
        factory { CreateListStore(get(), get(), get(), get()) }
        factory { ItemDetailStore(get(), get(), get(), get(), get(), get(), get(), get()) }
        factory { AccountStore(get(), get(), version = "0.0.0-interim", flags = emptyMap()) }
    }
