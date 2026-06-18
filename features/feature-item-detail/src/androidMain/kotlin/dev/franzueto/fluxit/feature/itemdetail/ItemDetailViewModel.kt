package dev.franzueto.fluxit.feature.itemdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.state.store.ItemDetailIntent
import dev.franzueto.fluxit.shared.state.store.ItemDetailStore
import kotlinx.coroutines.CoroutineScope

/**
 * Scopes the [ItemDetailStore] to the screen (plan/10 §8, Phase 05 §8). Like
 * `ListDetailViewModel`, it runs the store in [viewModelScope] so the item feed +
 * any in-flight save/capture are cancelled when the screen leaves the back stack,
 * and dispatches [ItemDetailIntent.Init] once on creation.
 *
 * No `SavedStateHandle` replay here: plan/10 §0 (f) leaves the §7 process-death
 * edit-overlay to v2, so the store simply reloads the item via `Init` on recreation.
 *
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
