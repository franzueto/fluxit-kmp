@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.lists

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.franzueto.fluxit.shared.state.store.ListsDashboardStore
import dev.franzueto.fluxit.shared.state.store.ListsIntent
import org.koin.compose.koinInject

/**
 * Koin/store glue for the Lists Dashboard (plan/07 §4). Resolves the
 * `factory`-scoped [ListsDashboardStore], collects its [state][ListsDashboardStore.state],
 * and forwards intents to the stateless [DashboardScreen].
 *
 * Slice 1 (`:features:feature-lists` scaffold) carries over the Phase-06
 * composition-root wiring verbatim; the real DS composition, effect→nav mapping,
 * and swipe/undo behaviours land in later Phase-07 slices.
 */
@Composable
fun DashboardRoute() {
    val store = koinInject<ListsDashboardStore>()
    val state by store.state.collectAsState()

    DashboardScreen(
        state = state,
        onIntent = store::dispatch,
    )
}

/** Convenience for `store::dispatch` readability at call sites. */
internal typealias OnListsIntent = (ListsIntent) -> Unit
