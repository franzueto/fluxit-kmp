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
 * Scopes the [ListDetailStore] to the screen (plan/08 §5, Phase 05 §8) and owns the
 * process-death persistence edge. Like `DashboardViewModel`, it runs the store in
 * [viewModelScope] so the feed subscription + undo timer are cancelled when the
 * screen leaves the back stack.
 *
 * **Process-death restoration (§5).** The composer text and show/hide-completed
 * flag are mirrored into [SavedStateHandle] (keys `composer:{listId}` /
 * `showCompleted:{listId}`). On (re)creation the saved values are replayed into the
 * store as intents — the shipped [ListDetailStore] exposes no `initialState`
 * constructor param (a §5 sketch that never landed), so replaying intents is the
 * restoration path. The pending-delete window is intentionally *not* persisted (§5).
 *
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

        // Restore typed-but-unsubmitted text + the show/hide preference (§5).
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
