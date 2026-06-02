@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.franzueto.fluxit.core.designsystem.components.FluxItEmptyState
import dev.franzueto.fluxit.core.designsystem.components.FluxItPrimaryButton
import dev.franzueto.fluxit.core.designsystem.components.FluxItSearchField
import dev.franzueto.fluxit.core.designsystem.components.FluxItTopBarLarge
import dev.franzueto.fluxit.core.designsystem.icons.FluxItIcons
import dev.franzueto.fluxit.core.designsystem.icons.List
import dev.franzueto.fluxit.core.designsystem.icons.Search
import dev.franzueto.fluxit.core.designsystem.icons.Settings
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.shared.state.store.ListsIntent
import dev.franzueto.fluxit.shared.state.store.ListsState
import dev.franzueto.fluxit.shared.state.store.LoadState
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Stateless Lists Dashboard (plan/07 §3/§4): renders [ListsState] and forwards
 * user actions through [onIntent]. Composed entirely from `core-designsystem`
 * primitives (Konsist literal-ban). The tab bar + FAB are the app shell's chrome
 * (rendered by the host around this screen); the sticky "My Lists" header lives
 * here.
 *
 * One-shot effects are handled in [DashboardRoute] and surfaced back as the
 * [undo] / [error] params, so this composable stays pure state-in → UI-out and
 * snapshot-friendly (Slice 8 renders it directly with synthetic [undo]/[error]).
 *
 * @param undo non-null while a delete's 5s undo window is open — drives the undo
 *   snackbar; [onUndo] dispatches [ListsIntent.UndoDeleteClicked].
 * @param error non-null when a transient error should show; [onErrorDismiss]
 *   clears it.
 */
@Composable
fun DashboardScreen(
    state: ListsState,
    onIntent: OnListsIntent,
    onOpenSettings: () -> Unit = {},
    undo: UndoSnackbarState? = null,
    onUndo: () -> Unit = {},
    error: String? = null,
    onErrorDismiss: () -> Unit = {},
) {
    // Captured once per composition so relative-time subtitles don't recompute /
    // drift on every scroll frame (§9). Acceptable for v1: no live ticking.
    val now = remember { Clock.System.now() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            FluxItTopBarLarge(
                title = "My Lists",
                trailingIcon = FluxItIcons.Settings,
                onTrailingClick = onOpenSettings,
                trailingContentDescription = "Settings",
            )
            FluxItSearchField(
                value = state.searchQuery,
                onValueChange = { onIntent(ListsIntent.SearchQueryChanged(it)) },
                searchIcon = FluxItIcons.Search,
                placeholder = "Search lists",
                modifier = Modifier.padding(horizontal = FluxItSpacing.containerPadding),
            )
            Box(modifier = Modifier.fillMaxSize().padding(top = FluxItSpacing.scaleMd)) {
                ListsBody(state = state, onIntent = onIntent, now = now)
            }
        }

        if (undo != null) {
            UndoSnackbar(
                state = undo,
                onUndo = onUndo,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = FluxItSpacing.containerPadding, vertical = FluxItSpacing.scaleLg),
            )
        }
        if (error != null) {
            ErrorSnackbar(
                message = error,
                onDismiss = onErrorDismiss,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = FluxItSpacing.containerPadding, vertical = FluxItSpacing.scaleLg),
            )
        }
    }
}

@Composable
private fun ListsBody(
    state: ListsState,
    onIntent: OnListsIntent,
    now: Instant,
) {
    when (val lists = state.lists) {
        LoadState.Loading -> SkeletonList()
        LoadState.Empty ->
            if (state.searchQuery.isNotBlank()) {
                CenteredEmpty(
                    title = "No lists matching \"${state.searchQuery}\"",
                    icon = FluxItIcons.Search,
                    message = "Try a different search.",
                )
            } else {
                CenteredEmpty(
                    title = "No lists yet",
                    icon = FluxItIcons.List,
                    message = "Tap + to create your first list.",
                )
            }
        is LoadState.Error ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleLg),
                ) {
                    FluxItEmptyState(title = lists.message, message = "Pull to refresh, or tap below to retry.")
                    FluxItPrimaryButton(label = "Retry", onClick = { onIntent(ListsIntent.Refresh) })
                }
            }
        is LoadState.Loaded ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        horizontal = FluxItSpacing.containerPadding,
                        vertical = FluxItSpacing.scaleSm,
                    ),
                verticalArrangement = Arrangement.spacedBy(FluxItSpacing.stackGap),
            ) {
                items(lists.value, key = { it.id.value }) { summary ->
                    DashboardListRow(
                        summary = summary,
                        now = now,
                        onOpen = { onIntent(ListsIntent.OpenList(summary.id)) },
                        onDelete = { onIntent(ListsIntent.DeleteListClicked(summary.id)) },
                    )
                }
            }
    }
}

@Composable
private fun CenteredEmpty(
    title: String,
    icon: ImageVector,
    message: String,
) {
    Box(modifier = Modifier.fillMaxWidth().fillMaxSize(), contentAlignment = Alignment.Center) {
        FluxItEmptyState(title = title, icon = icon, message = message)
    }
}
