package dev.franzueto.fluxit.shared.domain.usecase.photos

import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.domain.port.PhotoStorage
import dev.franzueto.fluxit.shared.domain.repository.PhotosRepository
import kotlinx.coroutines.flow.first

/**
 * Resolve a [PhotoId] to a platform-absolute uri an image loader can render
 * (Phase 04 §7) — backs the Edit-Item screen's photo preview (Phase 10 /
 * `plan/05` §4 `ItemDetailStore`).
 *
 * Reads the photo row via [PhotosRepository.observe]`.first()` for its
 * sandbox-relative `relativePath`, then hands that to
 * [PhotoStorage.resolveAbsolute]. A missing / tombstoned photo yields `null`
 * (the item points at nothing renderable) — a read edge, not an `Outcome`
 * failure (photos are immutable once ingested, so a single read suffices; see
 * [ObserveLists]'s reactive-read rationale).
 *
 * **Concurrency (§9):** caller dispatcher — any; this use case does not block.
 * It suspends only on the injected repository, which owns its dispatcher;
 * the domain stays dispatcher-agnostic (no `withContext`/`Dispatchers.*`).
 */
public class ResolvePhotoUri(
    private val photos: PhotosRepository,
    private val storage: PhotoStorage,
) {
    public suspend operator fun invoke(photoId: PhotoId): String? =
        photos.observe(photoId).first()?.let { storage.resolveAbsolute(it.relativePath) }
}
