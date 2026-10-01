package com.fluxit.di

import com.fluxit.data.DebugSeeder
import com.fluxit.feature.auth.AuthViewModel
import com.fluxit.feature.auth.SessionGateViewModel
import com.fluxit.feature.auth.SessionScopedViewModelStores
import com.fluxit.feature.createlist.CreateListViewModel
import com.fluxit.feature.dashboard.DashboardViewModel
import com.fluxit.feature.itemdetail.ItemDetailViewModel
import com.fluxit.feature.listdetail.ListDetailViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Platform module providing PhotoPicker, PhotoStorage, AuthRepository, and
 * (FB-207) the `ListRepository`/`ItemRepository` bindings.
 *
 * FB-702: each actual platform module binds Firebase unconditionally. UID paths and
 * listeners are resolved only by authenticated operations.
 */
expect fun platformModule(): Module

val appModule = module {
    single { DebugSeeder(get(), get()) }

    // FB-104: the first consumers of AuthRepository, which each `platformModule()`
    // binds (AndroidAuthRepository since FB-102, IosAuthRepository since FB-103). They
    // live in the shared module because the gate and the auth UI are common code; only
    // the adapter behind the interface is platform-specific.
    viewModel { SessionGateViewModel(get()) }
    viewModel { AuthViewModel(get()) }

    // FB-105: owns the ViewModelStore of the active session scope, so signing out (or
    // switching user) destroys every user-scoped ViewModel behind it. Resolved by the
    // gate from the ROOT ViewModelStoreOwner, never from the scope it manages.
    viewModel { SessionScopedViewModelStores() }

    // FB-404: DashboardViewModel/ListDetailViewModel/ItemDetailViewModel now each additionally
    // take AuthRepository, to derive their new fatal-session state from FB-101/FB-105's
    // existing session machinery (never a parallel signal) - see each ViewModel's constructor
    // KDoc.
    viewModel { DashboardViewModel(get(), get(), get()) }
    viewModel { (listId: String) -> ListDetailViewModel(listId, get(), get(), get()) }
    viewModel { (editingId: String?) -> CreateListViewModel(editingId, get()) }
    viewModel { (listId: String, itemId: String) -> ItemDetailViewModel(listId, itemId, get(), get(), get(), get(), get()) }
}
