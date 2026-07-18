package dev.franzueto.fluxit.shared.domain.repository

import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.Photo
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import kotlinx.coroutines.flow.Flow

public interface PhotosRepository {
    public suspend fun ingest(
        bytes: ByteArray,
        mime: String,
        width: Int,
        height: Int,
    ): Outcome<PhotoId, DataError>

    public fun observe(photoId: PhotoId): Flow<Photo?>

    public suspend fun deleteIfOrphaned(photoId: PhotoId): Outcome<Unit, DataError>
}
