package com.fluxit.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DataArray
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fluxit.domain.FluxListSummary
import com.fluxit.ui.components.EmptyState
import com.fluxit.ui.components.SwipeToDeleteContainer
import com.fluxit.ui.components.toImageVector
import com.fluxit.ui.theme.FluxCardShape
import com.fluxit.ui.theme.FluxSpacing
import com.fluxit.ui.theme.FluxType
import com.fluxit.ui.theme.toColor
import fluxit.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

const val DEBUG_SEED_ENABLED = true

@Composable
fun DashboardScreen(
    onOpenList: (String) -> Unit,
    onCreateList: () -> Unit,
    viewModel: DashboardViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val undoListId by viewModel.undoListId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listDeletedMessage = stringResource(Res.string.message_list_deleted)
    val undoLabel = stringResource(Res.string.action_undo)

    LaunchedEffect(undoListId, listDeletedMessage, undoLabel) {
        val id = undoListId ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = listDeletedMessage,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.dismissUndo()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .shadow(
                        16.dp,
                        CircleShape,
                        ambientColor = MaterialTheme.colorScheme.primary,
                        spotColor = MaterialTheme.colorScheme.primary,
                    )
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .clickable(onClick = onCreateList),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(Res.string.content_description_create_list),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(32.dp),
                )
            }
        },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = FluxSpacing.ContainerPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Person,
                        contentDescription = stringResource(Res.string.content_description_profile),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (DEBUG_SEED_ENABLED) {
                    IconButton(onClick = viewModel::seedSampleData) {
                        Icon(
                            Icons.Outlined.DataArray,
                            contentDescription = stringResource(Res.string.content_description_seed_sample_data),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = {}) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = stringResource(Res.string.content_description_settings),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }

            Text(
                text = stringResource(Res.string.dashboard_title),
                style = FluxType.DisplayLg,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
            )

            TextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        stringResource(Res.string.search_lists_placeholder),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                singleLine = true,
                shape = FluxCardShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )

            if (!state.isLoading && state.lists.isEmpty()) {
                EmptyState(
                    if (state.searchQuery.isBlank()) stringResource(Res.string.empty_lists)
                    else stringResource(Res.string.empty_search_results, state.searchQuery.trim()),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(FluxSpacing.RowGap),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 96.dp),
                ) {
                    items(state.lists, key = { it.list.id }) { summary ->
                        SwipeToDeleteContainer(onDelete = { viewModel.deleteList(summary.list.id) }) {
                            ListRow(summary = summary, onClick = { onOpenList(summary.list.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun subtitleFor(summary: FluxListSummary): String {
    val n = summary.totalItems
    val done = summary.completedItems
    return when {
        n == 0 -> stringResource(Res.string.no_items_yet)
        done in 1 until n -> pluralStringResource(
            Res.plurals.item_count_with_progress,
            n,
            n,
            done * 100 / n,
        )
        else -> pluralStringResource(Res.plurals.item_count, n, n)
    }
}

@Composable
private fun ListRow(summary: FluxListSummary, onClick: () -> Unit) {
    val accent = summary.list.color.toColor()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = FluxCardShape,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(56.dp).background(accent.copy(alpha = 0.2f), FluxCardShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(summary.list.icon.toImageVector(), contentDescription = null, tint = accent)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(summary.list.name, style = FluxType.TitleMd, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitleFor(summary),
                    style = FluxType.LabelSm,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
