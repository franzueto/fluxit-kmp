@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.itemdetail

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.franzueto.fluxit.core.designsystem.components.FluxItCard
import dev.franzueto.fluxit.core.designsystem.components.FluxItSectionHeader
import dev.franzueto.fluxit.core.designsystem.icons.Camera
import dev.franzueto.fluxit.core.designsystem.icons.FluxItIcons
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography
import dev.franzueto.fluxit.shared.state.store.ItemDetailIntent
import dev.franzueto.fluxit.shared.state.store.ItemDetailState
import dev.franzueto.fluxit.shared.state.store.PhotoPickSource
import dev.franzueto.fluxit.shared.state.store.PhotoStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * §1 Item Photo section: a header with an "Update" affordance plus the photo card
 * whose body follows [PhotoStatus] — loaded image (16:9, cropped), empty
 * tap-to-add state, a busy spinner while capturing, or an error with retry. The
 * card itself is tappable (§13 divergence — more discoverable on touch). The
 * source action sheet is state-driven ([ItemDetailState.showPhotoSourceSheet]).
 */
@Composable
internal fun PhotoSection(
    state: ItemDetailState,
    onIntent: OnItemDetailIntent,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleSm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FluxItSectionHeader(label = "ITEM PHOTO")
            TextButton(onClick = { onIntent(ItemDetailIntent.UpdatePhotoClicked) }) { Text("Update") }
        }
        PhotoCard(status = state.photoStatus, onIntent = onIntent)
    }
}

@Composable
private fun PhotoCard(
    status: PhotoStatus,
    onIntent: OnItemDetailIntent,
) {
    FluxItCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(PHOTO_ASPECT)
                .clickable { onIntent(ItemDetailIntent.UpdatePhotoClicked) },
    ) {
        when (status) {
            is PhotoStatus.Loaded -> LoadedPhoto(uri = status.uri)
            PhotoStatus.None -> EmptyPhoto()
            PhotoStatus.Capturing, PhotoStatus.Uploading -> BusyPhoto()
            PhotoStatus.Error -> ErrorPhoto()
        }
    }
}

@Composable
private fun LoadedPhoto(uri: String) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(uri)?.asImageBitmap() }.getOrNull() }
    }
    val image = bitmap
    if (image == null) {
        BusyPhoto()
    } else {
        Image(
            bitmap = image,
            contentDescription = "Item photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun EmptyPhoto() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector = FluxItIcons.Camera, contentDescription = null, tint = FluxItColors.textMuted)
        Text(text = "No photo yet", style = FluxItTypography.bodyMd, color = FluxItColors.textPrimary)
        Text(text = "Tap to add one", style = FluxItTypography.labelSm, color = FluxItColors.textMuted)
    }
}

@Composable
private fun BusyPhoto() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = FluxItColors.primaryBlue)
    }
}

@Composable
private fun ErrorPhoto() {
    Column(
        modifier = Modifier.fillMaxSize().padding(FluxItSpacing.scaleMd),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Couldn't add that photo.", style = FluxItTypography.bodyMd, color = FluxItColors.textPrimary)
        Text(text = "Tap to try again", style = FluxItTypography.labelSm, color = FluxItColors.textMuted)
    }
}

/** §1/§15 source action sheet — shown while [ItemDetailState.showPhotoSourceSheet]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoSourceSheet(
    state: ItemDetailState,
    onIntent: OnItemDetailIntent,
) {
    if (!state.showPhotoSourceSheet) return
    ModalBottomSheet(
        onDismissRequest = { onIntent(ItemDetailIntent.PhotoSourceSheetDismissed) },
        containerColor = FluxItColors.surfaceCard,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(FluxItSpacing.scaleMd),
            verticalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleSm),
        ) {
            SheetAction(label = "Take Photo") { onIntent(ItemDetailIntent.PhotoSourceSelected(PhotoPickSource.Camera)) }
            SheetAction(label = "Choose from Library") { onIntent(ItemDetailIntent.PhotoSourceSelected(PhotoPickSource.Library)) }
            // §15: "Remove Photo" appears only when a photo is attached.
            if (state.photoStatus is PhotoStatus.Loaded) {
                SheetAction(label = "Remove Photo") { onIntent(ItemDetailIntent.RemovePhotoClicked) }
            }
        }
    }
}

@Composable
private fun SheetAction(
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = FluxItTypography.bodyMd, color = FluxItColors.textPrimary, modifier = Modifier.fillMaxWidth())
    }
}

private const val PHOTO_ASPECT = 16f / 9f
