package dev.franzueto.fluxit.feature.itemdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.state.store.ItemDetailIntent
import dev.franzueto.fluxit.shared.state.store.ItemDetailStore
import kotlinx.coroutines.CoroutineScope

/**
 * [storeFactory] mints the store with [viewModelScope]; [ItemDetailRoute] wires it
 * to Koin (`koin.get { parametersOf(scope) }`).
 */
internal class ItemDetailViewModel(
    itemId: ItemId,
    storeFactory: (CoroutineScope) -> ItemDetailStore,
) : ViewModel() {
    val store: ItemDetailStore = storeFactory(viewModelScope)

    init {
        store.dispatch(ItemDetailIntent.Init(itemId))
    }
}
