package dev.franzueto.fluxit.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.franzueto.fluxit.shared.state.store.AccountStore
import kotlinx.coroutines.CoroutineScope

/**
 * [storeFactory] mints the store with that scope; the Account/Settings routes wire
 * it to Koin (`koin.get { parametersOf(scope) }`), threading the scope through the
 * `:shared:state` factory's optional-scope parameter.
 */
internal class AccountViewModel(
    storeFactory: (CoroutineScope) -> AccountStore,
) : ViewModel() {
    val store: AccountStore = storeFactory(viewModelScope)
}
