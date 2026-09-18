package com.fluxit.di

import com.fluxit.data.DebugSeeder
import com.fluxit.data.FluxItDatabase
import com.fluxit.data.RoomItemRepository
import com.fluxit.data.RoomListRepository
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListRepository
import com.fluxit.feature.auth.AuthViewModel
import com.fluxit.feature.auth.SessionGateViewModel
import com.fluxit.feature.createlist.CreateListViewModel
import com.fluxit.feature.dashboard.DashboardViewModel
import com.fluxit.feature.itemdetail.ItemDetailViewModel
import com.fluxit.feature.listdetail.ListDetailViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Platform module providing FluxItDatabase, PhotoPicker, and PhotoStorage. */
expect fun platformModule(): Module

val appModule = module {
    single<ListRepository> { RoomListRepository(get<FluxItDatabase>()) }
    single<ItemRepository> { RoomItemRepository(get<FluxItDatabase>()) }
    single { DebugSeeder(get(), get()) }

    // FB-104: the first consumers of AuthRepository, which each `platformModule()`
    // binds (AndroidAuthRepository since FB-102, IosAuthRepository since FB-103). They
    // live in the shared module because the gate and the auth UI are common code; only
    // the adapter behind the interface is platform-specific.
    viewModel { SessionGateViewModel(get()) }
    viewModel { AuthViewModel(get()) }

    viewModel { DashboardViewModel(get(), get()) }
    viewModel { (listId: String) -> ListDetailViewModel(listId, get(), get()) }
    viewModel { (editingId: String?) -> CreateListViewModel(editingId, get()) }
    viewModel { (itemId: String) -> ItemDetailViewModel(itemId, get(), get(), get(), get()) }
}
