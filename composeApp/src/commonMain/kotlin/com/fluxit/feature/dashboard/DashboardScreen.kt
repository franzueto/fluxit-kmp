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
import com.fluxit.ui.theme.FluxColors
import com.fluxit.ui.theme.FluxSpacing
import com.fluxit.ui.theme.FluxType
import com.fluxit.ui.theme.toColor
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

    LaunchedEffect(undoListId) {
        val id = undoListId ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "List deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.dismissUndo()
    }

    Scaffold(
        containerColor = FluxColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .shadow(16.dp, CircleShape, ambientColor = FluxColors.PrimaryBlue, spotColor = FluxColors.PrimaryBlue)
                    .background(FluxColors.PrimaryBlue, CircleShape)
                    .clickable(onClick = onCreateList),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Create list", tint = Color.White, modifier = Modifier.size(32.dp))
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
                    modifier = Modifier.size(40.dp).background(FluxColors.Surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Person, contentDescription = "Profile", tint = FluxColors.TextMuted)
                }
                Spacer(Modifier.weight(1f))
                if (DEBUG_SEED_ENABLED) {
                    IconButton(onClick = viewModel::seedSampleData) {
                        Icon(Icons.Outlined.DataArray, contentDescription = "Seed sample data", tint = FluxColors.TextMuted)
                    }
                }
                IconButton(onClick = {}) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = FluxColors.TextPrimary)
                }
            }

            Text(
                text = "My Lists",
                style = FluxType.DisplayLg,
                color = FluxColors.TextPrimary,
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
            )

            TextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search lists...", color = FluxColors.TextMuted) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = FluxColors.TextMuted) },
                singleLine = true,
                shape = FluxCardShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = FluxColors.Surface,
                    unfocusedContainerColor = FluxColors.Surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = FluxColors.PrimaryBlue,
                    focusedTextColor = FluxColors.TextPrimary,
                    unfocusedTextColor = FluxColors.TextPrimary,
                ),
            )

            if (!state.isLoading && state.lists.isEmpty()) {
                EmptyState(
                    if (state.searchQuery.isBlank()) "No lists yet — tap + to create one"
                    else "No lists match \"${state.searchQuery.trim()}\"",
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

private fun subtitleFor(summary: FluxListSummary): String {
    val n = summary.totalItems
    val done = summary.completedItems
    return when {
        n == 0 -> "No items yet"
        done in 1 until n -> "$n items · ${done * 100 / n}% completed"
        else -> "$n items"
    }
}

@Composable
private fun ListRow(summary: FluxListSummary, onClick: () -> Unit) {
    val accent = summary.list.color.toColor()
    Surface(
        color = FluxColors.Surface,
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
                Text(summary.list.name, style = FluxType.TitleMd, color = FluxColors.TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text(subtitleFor(summary), style = FluxType.LabelSm, color = FluxColors.TextMuted)
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = FluxColors.TextMuted,
            )
        }
    }
}
