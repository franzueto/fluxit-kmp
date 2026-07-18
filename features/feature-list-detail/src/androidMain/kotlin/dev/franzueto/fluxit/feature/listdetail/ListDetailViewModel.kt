package dev.franzueto.fluxit.feature.listdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.state.store.ListDetailIntent
import dev.franzueto.fluxit.shared.state.store.ListDetailStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * [storeFactory] mints the store with [viewModelScope]; [ListDetailRoute] wires it
 * to Koin (`koin.get { parametersOf(scope) }`).
 */
internal class ListDetailViewModel(
    private val savedState: SavedStateHandle,
    listId: ListId,
    storeFactory: (CoroutineScope) -> ListDetailStore,
) : ViewModel() {
    val store: ListDetailStore = storeFactory(viewModelScope)

    private val composerKey = "composer:${listId.value}"
    private val showCompletedKey = "showCompleted:${listId.value}"

    init {
        store.dispatch(ListDetailIntent.Init(listId))

        savedState.get<String>(composerKey)?.takeIf { it.isNotEmpty() }?.let {
            store.dispatch(ListDetailIntent.ComposerTextChanged(it))
        }
        // The store defaults showCompleted = true; only replay a saved `false`.
        if (savedState.get<Boolean>(showCompletedKey) == false) {
            store.dispatch(ListDetailIntent.ToggleShowCompleted)
        }

        // Mirror the two persisted fields back into SavedStateHandle on every change.
        store.state
            .onEach { state ->
                savedState[composerKey] = state.composerText
                savedState[showCompletedKey] = state.showCompleted
            }.launchIn(viewModelScope)
    }
}
