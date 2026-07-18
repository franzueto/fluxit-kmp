package dev.franzueto.fluxit.shared.state.di

import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.state.debug.SeedSampleData
import dev.franzueto.fluxit.shared.state.store.AccountStore
import dev.franzueto.fluxit.shared.state.store.CreateListStore
import dev.franzueto.fluxit.shared.state.store.EditListDeps
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
 */
public val stateModule: Module =
    module {
        factory<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        single { RootStore(get(), get(), get()) }
        factory { params ->
            ListsDashboardStore(params.getOrNull<CoroutineScope>() ?: get(), get(), get(), get(), get(), get())
        }
        factory { params ->
            ListDetailStore(params.getOrNull<CoroutineScope>() ?: get(), get(), get(), get(), get(), get(), get(), get())
        }
        // Optional params (either, both, or neither): a CoroutineScope (Android
        factory { params ->
            CreateListStore(
                scope = params.getOrNull<CoroutineScope>() ?: get(),
                logger = get(),
                createList = get(),
                scheduleReminder = get(),
                edit = EditListDeps(get(), get(), get()),
                config = get(),
                editingId = params.getOrNull<ListId>(),
            )
        }
        // Optional CoroutineScope param: Android passes viewModelScope (so the item
        // feed is cancelled when the screen leaves); iOS/tests take the fresh-scope
        // fallback. The item id arrives later via ItemDetailIntent.Init.
        factory { params ->
            ItemDetailStore(params.getOrNull<CoroutineScope>() ?: get(), get(), get(), get(), get(), get(), get(), get())
        }
        factory { params ->
            AccountStore(params.getOrNull<CoroutineScope>() ?: get(), get(), version = "0.0.0-interim", flags = emptyMap())
        }
        // build — the *button* is stripped from release via source-set selection.
        factory { SeedSampleData(get(), get(), get()) }
    }
