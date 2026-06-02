package dev.franzueto.fluxit.feature.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.franzueto.fluxit.shared.state.store.ListsDashboardStore
import kotlinx.coroutines.CoroutineScope

/**
 * Scopes the [ListsDashboardStore] to the screen (plan/07 §4, Phase 05 §8). It
 * has no logic of its own: it exists only so the store runs in [viewModelScope],
 * which survives configuration changes and is cancelled when the dashboard leaves
 * the back stack — stopping the store's feed subscription and undo timers.
 *
 * [storeFactory] mints the store with that scope; [DashboardRoute] wires it to
 * Koin (`koin.get { parametersOf(scope) }`), which threads the scope through the
 * `:shared:state` factory's optional-scope parameter.
 */
internal class DashboardViewModel(
    storeFactory: (CoroutineScope) -> ListsDashboardStore,
) : ViewModel() {
    val store: ListsDashboardStore = storeFactory(viewModelScope)
}
