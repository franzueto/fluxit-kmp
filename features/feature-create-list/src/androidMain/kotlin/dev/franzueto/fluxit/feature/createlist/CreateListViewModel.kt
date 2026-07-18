package dev.franzueto.fluxit.feature.createlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.franzueto.fluxit.shared.state.store.CreateListStore
import kotlinx.coroutines.CoroutineScope

/**
 * [storeFactory] mints the store with [viewModelScope]; [CreateListRoute] wires
 * it to Koin (`koin.get { parametersOf(scope, editingId) }`).
 */
internal class CreateListViewModel(
    storeFactory: (CoroutineScope) -> CreateListStore,
) : ViewModel() {
    val store: CreateListStore = storeFactory(viewModelScope)
}
