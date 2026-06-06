@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.listdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.state.store.ListDetailEffect
import dev.franzueto.fluxit.shared.state.store.ListDetailIntent
import kotlinx.coroutines.delay
import org.koin.compose.getKoin
import org.koin.core.parameter.parametersOf

/**
 * Koin/ViewModel glue for the List Detail screen (plan/08 §9). Builds a
 * [ListDetailViewModel] (scoping the store to `viewModelScope` + owning the §5
 * SavedStateHandle persistence), collects state, and maps the store's one-shot
 * [ListDetailEffect]s to navigation callbacks + the undo/error snackbar UI state
 * + the list-actions sheet visibility, before handing everything to the stateless
 * [ListDetailScreen].
 *
 * The `when` over [ListDetailEffect] is exhaustive, so a new effect variant breaks
 * the build here (the §11 effect-mapping contract).
 *
 * @param listId the route argument (`list/{listId}`); parsed into a [ListId].
 * @param onBack pop the detail route.
 * @param onOpenEditItem push the edit-item route (Phase 10 destination).
 */
@Composable
fun ListDetailRoute(
    listId: String,
    onBack: () -> Unit,
    onOpenEditItem: (ItemId) -> Unit,
) {
    val koin = getKoin()
    val id = remember(listId) { ListId(listId) }
    val viewModel =
        viewModel {
            ListDetailViewModel(createSavedStateHandle(), id) { scope -> koin.get { parametersOf(scope) } }
        }
    val store = viewModel.store
    val state by store.state.collectAsState()

    var undo by remember { mutableStateOf<UndoSnackbarState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(store) {
        store.effects.collect { effect ->
            when (effect) {
                ListDetailEffect.NavigateBack -> onBack()
                is ListDetailEffect.NavigateToEditItem -> onOpenEditItem(effect.id)
                ListDetailEffect.OpenListMenu -> showMenu = true
                is ListDetailEffect.ShowUndoSnackbar -> undo = UndoSnackbarState(effect.title, progress = 1f)
                is ListDetailEffect.ShowError -> error = effect.message
            }
        }
    }

    // The store closes the undo window (5s expiry, undo tap, or re-delete) by
    // clearing pendingDelete — mirror that into the snackbar's visibility.
    LaunchedEffect(state.pendingDelete == null) {
        if (state.pendingDelete == null) undo = null
    }

    // Animate the countdown bar (1f → 0f) for the open window. Keyed on the
    // deleted item's title so a re-delete restarts the bar.
    LaunchedEffect(undo?.listName) {
        if (undo == null) return@LaunchedEffect
        var elapsed = 0L
        while (elapsed < UNDO_WINDOW_MS && undo != null) {
            delay(TICK_MS)
            elapsed += TICK_MS
            undo = undo?.copy(progress = (1f - elapsed.toFloat() / UNDO_WINDOW_MS).coerceIn(0f, 1f))
        }
    }

    ListDetailScreen(
        state = state,
        onIntent = store::dispatch,
        onBack = { store.dispatch(ListDetailIntent.BackClicked) },
        chrome =
            ListDetailChrome(
                undo = undo,
                onUndo = { store.dispatch(ListDetailIntent.UndoItemDeleteClicked) },
                error = error,
                onErrorDismiss = { error = null },
                showMenu = showMenu,
                onDismissMenu = { showMenu = false },
            ),
    )
}

/** Convenience for `store::dispatch` readability at call sites. */
internal typealias OnListDetailIntent = (ListDetailIntent) -> Unit

private const val UNDO_WINDOW_MS = 5_000L
private const val TICK_MS = 50L
