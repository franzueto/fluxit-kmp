@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.lists

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.state.navigation.Tab
import dev.franzueto.fluxit.shared.state.store.ListsEffect
import dev.franzueto.fluxit.shared.state.store.ListsIntent
import kotlinx.coroutines.delay
import org.koin.compose.getKoin
import org.koin.core.parameter.parametersOf

/**
 * Koin/ViewModel glue for the Lists Dashboard (plan/07 §4/§6). Builds a
 * [DashboardViewModel] (scoping the store to `viewModelScope`), collects state,
 * and maps the store's one-shot [ListsEffect]s to navigation callbacks + the
 * undo/error snackbar UI state, before handing everything to the stateless
 * [DashboardScreen].
 *
 * The `when` over [ListsEffect] is exhaustive, so a new effect variant breaks the
 * build here (the §6 effect-mapping contract).
 *
 * @param onOpenList push the list-detail route (Phase 08 destination).
 * @param onCreateList present the create-list modal (Phase 09 destination).
 * @param onComingSoon route a deferred tab to its placeholder. Dead path in the
 *   current shell (the tab bar is owned by `RootStore`, so the dashboard never
 *   dispatches `TabSelected`); handled for exhaustiveness.
 * @param onOpenSettings open the settings screen from the header gear.
 */
@Composable
fun DashboardRoute(
    onOpenList: (ListId) -> Unit,
    onCreateList: () -> Unit,
    onComingSoon: (Tab) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val koin = getKoin()
    val viewModel = viewModel { DashboardViewModel { scope -> koin.get { parametersOf(scope) } } }
    val store = viewModel.store
    val state by store.state.collectAsState()

    var undo by remember { mutableStateOf<UndoSnackbarState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(store) {
        store.effects.collect { effect ->
            when (effect) {
                is ListsEffect.NavigateToListDetail -> onOpenList(effect.id)
                ListsEffect.NavigateToCreateList -> onCreateList()
                is ListsEffect.NavigateToTab -> onComingSoon(effect.tab)
                is ListsEffect.ShowUndoSnackbar -> undo = UndoSnackbarState(effect.name, progress = 1f)
                is ListsEffect.ShowError -> error = effect.message
            }
        }
    }

    // The store closes the window (5s expiry, undo tap, or re-delete) by clearing
    // pendingDelete — mirror that into the snackbar's visibility.
    LaunchedEffect(state.pendingDelete == null) {
        if (state.pendingDelete == null) undo = null
    }

    // Animate the countdown bar (1f → 0f) for the open window. Keyed on the
    // deleted list's name so a re-delete restarts the bar.
    LaunchedEffect(undo?.listName) {
        if (undo == null) return@LaunchedEffect
        var elapsed = 0L
        while (elapsed < UNDO_WINDOW_MS && undo != null) {
            delay(TICK_MS)
            elapsed += TICK_MS
            undo = undo?.copy(progress = (1f - elapsed.toFloat() / UNDO_WINDOW_MS).coerceIn(0f, 1f))
        }
    }

    DashboardScreen(
        state = state,
        onIntent = store::dispatch,
        onOpenSettings = onOpenSettings,
        undo = undo,
        onUndo = { store.dispatch(ListsIntent.UndoDeleteClicked) },
        error = error,
        onErrorDismiss = { error = null },
    )
}

/** Convenience for `store::dispatch` readability at call sites. */
internal typealias OnListsIntent = (ListsIntent) -> Unit

private const val UNDO_WINDOW_MS = 5_000L
private const val TICK_MS = 50L
