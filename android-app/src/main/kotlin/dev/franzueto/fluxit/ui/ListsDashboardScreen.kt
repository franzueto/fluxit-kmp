@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.shared.domain.model.ListSummary
import dev.franzueto.fluxit.shared.state.store.ListsDashboardStore
import dev.franzueto.fluxit.shared.state.store.ListsIntent
import dev.franzueto.fluxit.shared.state.store.LoadState
import org.koin.compose.koinInject

/**
 * The Lists Dashboard, wired to [ListsDashboardStore] (Phase 06 Slice 7). Slice 7
 * proves the composition root: state renders, the search field dispatches
 * [ListsIntent.SearchQueryChanged] into the real use-case feed. Optimistic
 * delete/undo, navigation effects, and the polished design-system rows land in
 * the Lists feature phase / Slice 8 e2e.
 */
@Composable
fun ListsDashboardScreen() {
    val store = koinInject<ListsDashboardStore>()
    val state by store.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(FluxItSpacing.containerPadding)) {
        Text("Lists", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { store.dispatch(ListsIntent.SearchQueryChanged(it)) },
            placeholder = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = FluxItSpacing.scaleMd),
        )

        when (val lists = state.lists) {
            LoadState.Loading ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            LoadState.Empty ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No lists yet")
                }
            is LoadState.Error ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(lists.message)
                }
            is LoadState.Loaded ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(FluxItSpacing.stackGap)) {
                    items(lists.value, key = { it.id.value }) { summary ->
                        ListRow(summary = summary, onClick = { store.dispatch(ListsIntent.OpenList(summary.id)) })
                    }
                }
        }
    }
}

@Composable
private fun ListRow(
    summary: ListSummary,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = FluxItSpacing.scaleSm),
    ) {
        Text(summary.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Text(
            "${summary.completedItems}/${summary.totalItems} done",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )
    }
}
