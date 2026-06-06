@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.listdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.franzueto.fluxit.core.designsystem.theme.FluxItTheme
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.Item
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ItemsSection
import dev.franzueto.fluxit.shared.domain.model.ListDetail
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.state.store.ListDetailState
import dev.franzueto.fluxit.shared.state.store.LoadState
import kotlinx.datetime.Instant

private val EPOCH = Instant.fromEpochSeconds(0)

private fun sampleItem(
    id: String,
    title: String,
    completed: Boolean,
    subtitle: String? = null,
): Item =
    Item(
        id = ItemId(id),
        listId = ListId("list-1"),
        title = title,
        subtitle = subtitle,
        description = null,
        isCompleted = completed,
        isStarred = false,
        photoId = null,
        createdAt = EPOCH,
        updatedAt = EPOCH,
    )

private val sampleHeader =
    ListDetail(
        id = ListId("list-1"),
        name = "Weekly Groceries",
        icon = FluxItIconRef.CART,
        color = ColorToken.ACCENT_EMERALD,
        isStarred = false,
        createdAt = EPOCH,
        updatedAt = EPOCH,
    )

private val sampleSection =
    ItemsSection(
        active =
            listOf(
                sampleItem("a1", "Organic Bananas", completed = false, subtitle = "Produce Section"),
                sampleItem("a2", "Almond Milk", completed = false),
                sampleItem("a3", "Sourdough Bread", completed = false),
            ),
        completed =
            listOf(
                sampleItem("c1", "Eggs", completed = true),
                sampleItem("c2", "Coffee", completed = true),
            ),
        total = 5,
        completedCount = 2,
    )

@Preview
@Composable
private fun ListDetailPopulatedPreview() {
    FluxItTheme {
        ListDetailScreen(
            state =
                ListDetailState(
                    header = LoadState.Loaded(sampleHeader),
                    sections = LoadState.Loaded(sampleSection),
                ),
            onIntent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun ListDetailEmptyPreview() {
    FluxItTheme {
        ListDetailScreen(
            state =
                ListDetailState(
                    header = LoadState.Loaded(sampleHeader),
                    sections = LoadState.Empty,
                ),
            onIntent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun ListDetailComposerWithTextPreview() {
    FluxItTheme {
        ListDetailScreen(
            state =
                ListDetailState(
                    header = LoadState.Loaded(sampleHeader),
                    sections = LoadState.Loaded(sampleSection),
                    composerText = "Olive oil",
                ),
            onIntent = {},
            onBack = {},
        )
    }
}
