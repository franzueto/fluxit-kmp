@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.itemdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.franzueto.fluxit.core.designsystem.theme.FluxItTheme
import dev.franzueto.fluxit.shared.domain.model.Item
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ItemPatch
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.state.store.ItemDetailState
import dev.franzueto.fluxit.shared.state.store.LoadState
import dev.franzueto.fluxit.shared.state.store.NameValidation
import dev.franzueto.fluxit.shared.state.store.PhotoStatus
import kotlinx.datetime.Instant

// The §14 snapshot matrix, rendered as previews instead (snapshot infra is
// deferred to v2 — plan/10 §0 decision e).

private val SAMPLE_ITEM =
    Item(
        id = ItemId("item-1"),
        listId = ListId("list-1"),
        title = "Olive oil",
        subtitle = null,
        description = "Extra virgin, 1L",
        isCompleted = false,
        isStarred = false,
        photoId = null,
        createdAt = Instant.fromEpochSeconds(1_700_000_000),
        updatedAt = Instant.fromEpochSeconds(1_718_700_000),
    )

private fun loadedState(
    title: String = SAMPLE_ITEM.title,
    description: String? = SAMPLE_ITEM.description,
    dirty: Boolean = false,
    photoStatus: PhotoStatus = PhotoStatus.None,
    submitting: Boolean = false,
    confirmDelete: Boolean = false,
    titleValidation: NameValidation = NameValidation.Valid,
): ItemDetailState =
    ItemDetailState(
        item = LoadState.Loaded(SAMPLE_ITEM),
        editing = ItemPatch(title = title, subtitle = null, description = description, photoId = null),
        dirty = dirty,
        photoStatus = photoStatus,
        submitting = submitting,
        confirmDelete = confirmDelete,
        titleValidation = titleValidation,
    )

@Preview(showBackground = true)
@Composable
private fun PreviewLoadedNoPhoto() {
    FluxItTheme { ItemDetailScreen(state = loadedState(), onIntent = {}) }
}

@Preview(showBackground = true)
@Composable
private fun PreviewDirty() {
    FluxItTheme { ItemDetailScreen(state = loadedState(title = "Olive oil (organic)", dirty = true), onIntent = {}) }
}

@Preview(showBackground = true)
@Composable
private fun PreviewCapturing() {
    FluxItTheme { ItemDetailScreen(state = loadedState(photoStatus = PhotoStatus.Capturing), onIntent = {}) }
}

@Preview(showBackground = true)
@Composable
private fun PreviewSaving() {
    FluxItTheme { ItemDetailScreen(state = loadedState(dirty = true, submitting = true), onIntent = {}) }
}

@Preview(showBackground = true)
@Composable
private fun PreviewTitleError() {
    FluxItTheme {
        ItemDetailScreen(state = loadedState(title = "", dirty = true, titleValidation = NameValidation.Empty), onIntent = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewDeleteConfirm() {
    FluxItTheme { ItemDetailScreen(state = loadedState(confirmDelete = true), onIntent = {}) }
}

@Preview(showBackground = true)
@Composable
private fun PreviewPermissionBanner() {
    FluxItTheme {
        ItemDetailScreen(
            state = loadedState(),
            onIntent = {},
            chrome = ItemDetailChrome(permissionBanner = PermissionTarget.Camera),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewLoading() {
    FluxItTheme { ItemDetailScreen(state = ItemDetailState(item = LoadState.Loading), onIntent = {}) }
}
