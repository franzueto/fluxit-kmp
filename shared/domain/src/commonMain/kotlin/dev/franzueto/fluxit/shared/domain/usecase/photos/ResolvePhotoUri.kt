package dev.franzueto.fluxit.shared.domain.usecase.photos

import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.domain.port.PhotoStorage
import dev.franzueto.fluxit.shared.domain.repository.PhotosRepository
import kotlinx.coroutines.flow.first

/**
 * Reads the photo row via [PhotosRepository.observe]`.first()` for its
 * sandbox-relative `relativePath`, then hands that to
 * [PhotoStorage.resolveAbsolute]. A missing / tombstoned photo yields `null`
 * (the item points at nothing renderable) — a read edge, not an `Outcome`
 * failure (photos are immutable once ingested, so a single read suffices; see
 * [ObserveLists]'s reactive-read rationale).
 */
public class ResolvePhotoUri(
    private val photos: PhotosRepository,
    private val storage: PhotoStorage,
) {
    public suspend operator fun invoke(photoId: PhotoId): String? =
        photos.observe(photoId).first()?.let { storage.resolveAbsolute(it.relativePath) }
}
