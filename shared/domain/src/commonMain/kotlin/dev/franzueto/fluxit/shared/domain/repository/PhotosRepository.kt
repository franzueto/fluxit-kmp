package dev.franzueto.fluxit.shared.domain.repository

import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.Photo
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for captured photos (Phase 03 §5, last slice).
 * The repo orchestrates the §7 file + row contract: file write happens
 * first; if the row insert fails the file is cleaned up before returning,
 * so a crash can leave at most one orphan file (which the photo janitor
 * sweeps via [deleteIfOrphaned] + the §7 24h grace window).
 */
public interface PhotosRepository {
    /**
     * Writes [bytes] to [dev.franzueto.fluxit.shared.domain.port.PhotoStorage]
     * + inserts the row. Caller pre-encoded to JPEG q=0.85 max-dim 2048
     * (Phase 06 `:platform:platform-photo` handles this above the repo).
     */
    public suspend fun ingest(
        bytes: ByteArray,
        mime: String,
        width: Int,
        height: Int,
    ): Outcome<PhotoId, DataError>

    public fun observe(photoId: PhotoId): Flow<Photo?>

    /**
     * Soft-deletes the photo row iff no live item still references it.
     * No-op (and Ok) when the photo is still referenced — the §7 janitor
     * re-checks on the 24h cycle. Hard delete + file removal is the
     * janitor's job, not this method's.
     */
    public suspend fun deleteIfOrphaned(photoId: PhotoId): Outcome<Unit, DataError>
}
