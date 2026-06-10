package dev.franzueto.fluxit.feature.createlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.franzueto.fluxit.shared.state.store.CreateListStore
import kotlinx.coroutines.CoroutineScope

/**
 * Scopes the [CreateListStore] to the modal (plan/09 §10, Phase 05 §8). Like
 * `ListDetailViewModel`, it runs the store in [viewModelScope] so the edit-mode
 * prefill and any in-flight submit are cancelled when the modal leaves the back
 * stack. The store itself owns all form state (the create/edit mode split rides
 * in via the Koin `editingId` parameter), so unlike list detail there is no
 * SavedStateHandle replay here — `plan/09` defines no process-death contract for
 * this screen.
 *
 * [storeFactory] mints the store with [viewModelScope]; [CreateListRoute] wires
 * it to Koin (`koin.get { parametersOf(scope, editingId) }`).
 */
internal class CreateListViewModel(
    storeFactory: (CoroutineScope) -> CreateListStore,
) : ViewModel() {
    val store: CreateListStore = storeFactory(viewModelScope)
}
