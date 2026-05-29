package dev.franzueto.fluxit.shared.domain.usecase.photos

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.domain.port.PhotoStorage
import dev.franzueto.fluxit.shared.domain.repository.PhotosRepository
import kotlinx.coroutines.flow.first

/**
 * Garbage-collect a single photo if no live item still references it
 * (Phase 04 §7). Returns `true` when the file was actually reclaimed.
 *
 * Flow:
 * 1. Read the photo's `relativePath` via `observe(id).first()` — a photo
 *    that's already gone is a no-op `Ok(false)`.
 * 2. `PhotosRepository.deleteIfOrphaned` soft-deletes the row **iff** it's
 *    unreferenced (no-op + `Ok` when still in use, per its contract).
 * 3. Re-read: if the row is now gone the photo was orphaned, so delete the
 *    backing file via [PhotoStorage.delete] and return `Ok(true)`. If the
 *    row survived it's still referenced — leave the file and return `Ok(false)`.
 *
 * **Spec/reality reconciliation:** the §7 punch list described a batch
 * `selectOrphaned(olderThan = 24h)` sweep, but the shipped `PhotosRepository`
 * exposes no enumeration primitive (only single-photo `observe` + `ingest` +
 * `deleteIfOrphaned`). So this ships as a **per-photo** janitor — the form
 * `DetachPhotoFromItem` needs and the form a future batch sweep would call in
 * a loop. The 24h-grace batch scan is deferred until the data layer surfaces
 * a `selectOrphaned` query.
 */
public class PhotoJanitor(
    private val photos: PhotosRepository,
    private val storage: PhotoStorage,
) {
    public suspend operator fun invoke(photoId: PhotoId): Outcome<Boolean, DomainError> {
        val path =
            photos.observe(photoId).first()?.relativePath
                ?: return Outcome.Ok(false) // already gone

        when (val swept = photos.deleteIfOrphaned(photoId).mapError { it.toDomain(entity = "Photo") }) {
            is Outcome.Err -> return swept
            is Outcome.Ok -> Unit
        }

        return if (photos.observe(photoId).first() == null) {
            storage.delete(path)
            Outcome.Ok(true)
        } else {
            // Still referenced — deleteIfOrphaned left the row in place.
            Outcome.Ok(false)
        }
    }
}
