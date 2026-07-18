@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.createlist

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.state.store.CreateListEffect
import dev.franzueto.fluxit.shared.state.store.CreateListIntent
import org.koin.compose.getKoin
import org.koin.core.parameter.parametersOf

@Composable
fun CreateListRoute(
    editingId: String?,
    onDismiss: () -> Unit,
    onCreated: (ListId) -> Unit,
) {
    val koin = getKoin()
    val id = remember(editingId) { editingId?.let(::ListId) }
    val viewModel =
        viewModel {
            CreateListViewModel { scope ->
                koin.get { if (id != null) parametersOf(scope, id) else parametersOf(scope) }
            }
        }
    val store = viewModel.store
    val state by store.state.collectAsState()

    var confirmDiscard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(store) {
        store.effects.collect { effect ->
            when (effect) {
                CreateListEffect.Dismiss -> onDismiss()
                CreateListEffect.ConfirmDiscard -> confirmDiscard = true
                is CreateListEffect.NavigateToListDetail -> onCreated(effect.newListId)
                is CreateListEffect.ShowError -> error = effect.message
                // Unreachable in v1: the Reminder Settings row only dispatches
                // when ConfigKey.RemindersEditorEnabled is on, and the flag ships
                CreateListEffect.NavigateToReminderSettings -> Unit
            }
        }
    }

    BackHandler { store.dispatch(CreateListIntent.CancelClicked) }

    CreateListScreen(
        state = state,
        onIntent = store::dispatch,
        chrome =
            CreateListChrome(
                error = error,
                onErrorDismiss = { error = null },
                confirmDiscard = confirmDiscard,
                onDiscard = {
                    confirmDiscard = false
                    store.dispatch(CreateListIntent.DiscardConfirmed)
                },
                onKeepEditing = { confirmDiscard = false },
            ),
    )
}

/** Convenience for `store::dispatch` readability at call sites. */
internal typealias OnCreateListIntent = (CreateListIntent) -> Unit
