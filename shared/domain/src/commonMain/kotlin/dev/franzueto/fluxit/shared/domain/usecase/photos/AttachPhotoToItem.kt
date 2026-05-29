package dev.franzueto.fluxit.shared.domain.usecase.photos

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Optional
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.map
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.domain.port.CapturedPhoto
import dev.franzueto.fluxit.shared.domain.port.PhotoCapture
import dev.franzueto.fluxit.shared.domain.repository.PhotosRepository
import dev.franzueto.fluxit.shared.domain.usecase.items.UpdateItemDetails

/** Where an attached photo comes from (Phase 04 §7). */
public enum class PhotoSource {
    CAMERA,
    LIBRARY,
}

/**
 * Attach a freshly captured / picked photo to an item (Phase 04 §7):
 * acquire bytes → ingest the row+file → point the item at the new photo.
 *
 * 1. [PhotoCapture] opens the camera or system picker per [PhotoSource]; a
 *    [dev.franzueto.fluxit.shared.domain.port.CaptureError] surfaces as
 *    [DomainError.CaptureFailure] (incl. `UserCancelled`, which the UI
 *    treats as a quiet abort, not an error banner).
 * 2. [PhotosRepository.ingest] writes the file then the row (its own
 *    file-first-then-row contract), minting the [PhotoId].
 * 3. The item's `photo_id` is set by composing [UpdateItemDetails] with
 *    `photoId = Optional.Set(id)` — which reads the current item and emits a
 *    complete `ItemPatch`, so a missing item is [DomainError.NotFound].
 *
 * **Edge:** if the item is gone by step 3 the ingested photo is left
 * unreferenced; [PhotoJanitor] reclaims it on the next sweep. Accepted over
 * a pre-check race that can't be closed without a transaction spanning two
 * repositories.
 *
 *
 * **Concurrency (§9):** caller dispatcher — any; this use case does not block.
 * It suspends only on the injected repository/port, which owns its dispatcher;
 * the domain stays dispatcher-agnostic (no `withContext`/`Dispatchers.*`).
 */
public class AttachPhotoToItem(
    private val photos: PhotosRepository,
    private val capture: PhotoCapture,
    private val updateItemDetails: UpdateItemDetails,
) {
    public suspend operator fun invoke(
        itemId: ItemId,
        source: PhotoSource,
    ): Outcome<PhotoId, DomainError> {
        val captured: CapturedPhoto =
            when (
                val result =
                    when (source) {
                        PhotoSource.CAMERA -> capture.capture()
                        PhotoSource.LIBRARY -> capture.pickFromLibrary()
                    }
            ) {
                is Outcome.Err -> return Outcome.Err(DomainError.CaptureFailure(reason = result.error))
                is Outcome.Ok -> result.value
            }

        val photoId =
            when (
                val ingested =
                    photos
                        .ingest(captured.bytes, captured.mime, captured.widthPx, captured.heightPx)
                        .mapError { it.toDomain(entity = "Photo") }
            ) {
                is Outcome.Err -> return ingested
                is Outcome.Ok -> ingested.value
            }

        return updateItemDetails(itemId, photoId = Optional.Set(photoId)).map { photoId }
    }
}
