@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.lists

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.shared.domain.model.ListSummary
import dev.franzueto.fluxit.shared.state.store.ListsIntent
import dev.franzueto.fluxit.shared.state.store.ListsState
import dev.franzueto.fluxit.shared.state.store.LoadState

/**
 * Stateless Lists Dashboard (plan/07 §4): renders [ListsState] and forwards user
 * actions through [onIntent]. Slice 1 keeps the Phase-06 minimal composition
 * (plain Material3 rows + search field) while the screen moves into
 * `:features:feature-lists`; the real design-system composition — sticky header,
 * `FluxItDashboardListItem`, swipe-to-delete + undo snackbar — replaces this body
 * in the dashboard-screen slice.
 */
@Composable
fun DashboardScreen(
    state: ListsState,
    onIntent: OnListsIntent,
) {
    Column(modifier = Modifier.fillMaxSize().padding(FluxItSpacing.containerPadding)) {
        Text("Lists", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { onIntent(ListsIntent.SearchQueryChanged(it)) },
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
                        ListRow(summary = summary, onClick = { onIntent(ListsIntent.OpenList(summary.id)) })
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
        Text(summary.name, style = MaterialTheme.typography.titleMedium)
        Text(
            "${summary.completedItems}/${summary.totalItems} done",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
