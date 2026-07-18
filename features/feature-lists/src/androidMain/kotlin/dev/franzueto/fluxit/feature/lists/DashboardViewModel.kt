package dev.franzueto.fluxit.feature.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.franzueto.fluxit.shared.state.store.ListsDashboardStore
import kotlinx.coroutines.CoroutineScope

/**
 * [storeFactory] mints the store with that scope; [DashboardRoute] wires it to
 * Koin (`koin.get { parametersOf(scope) }`), which threads the scope through the
 * `:shared:state` factory's optional-scope parameter.
 */
internal class DashboardViewModel(
    storeFactory: (CoroutineScope) -> ListsDashboardStore,
) : ViewModel() {
    val store: ListsDashboardStore = storeFactory(viewModelScope)
}
