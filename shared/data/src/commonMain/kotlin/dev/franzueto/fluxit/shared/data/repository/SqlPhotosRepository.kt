package dev.franzueto.fluxit.shared.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import dev.franzueto.fluxit.core.utils.IdGenerator
import dev.franzueto.fluxit.shared.data.db.FluxItDatabase
import dev.franzueto.fluxit.shared.data.mapper.toDomain
import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.Photo
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.domain.port.PhotoStorage
import dev.franzueto.fluxit.shared.domain.repository.PhotosRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

/**
 * SQLDelight-backed [PhotosRepository] (Phase 03 §5, 4/4). Closes §5
 * and binds the §7 [PhotoStorage] port: file write happens first; if
 * the row insert fails the file is cleaned up before returning so a
 * crash can leak at most one orphan file (the §7 janitor sweeps it
 * after the 24h grace window).
 */
public class SqlPhotosRepository(
    private val database: FluxItDatabase,
    private val storage: PhotoStorage,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.System,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PhotosRepository {
    private val queries get() = database.photosQueries

    @Suppress("TooGenericExceptionCaught")
    override suspend fun ingest(
        bytes: ByteArray,
        mime: String,
        width: Int,
        height: Int,
    ): Outcome<PhotoId, DataError> {
        // File write sits outside `guard {}` because we need the captured
        // `path` to clean up on row-insert failure — wrapping the whole
        // sequence in `guard` would lose that handle.
        val path =
            try {
                storage.write(bytes, mime)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (e: Exception) {
                return Outcome.Err(DataError.Storage(e))
            }
        val id = PhotoId(ids.newId())
        return try {
            queries.insert(
                id = id.value,
                relative_path = path,
                mime_type = mime,
                width_px = width.toLong(),
                height_px = height.toLong(),
                byte_size = bytes.size.toLong(),
                created_at = clock.now(),
            )
            Outcome.Ok(id)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // Cancelled mid-insert: still attempt cleanup before re-throwing
            // so the file doesn't linger as a guaranteed orphan.
            runCatching { storage.delete(path) }
            throw cancel
        } catch (e: Exception) {
            runCatching { storage.delete(path) }
            Outcome.Err(DataError.Storage(e))
        }
    }

    override fun observe(photoId: PhotoId): Flow<Photo?> =
        queries
            .selectById(photoId.value)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map { it?.toDomain() }

    override suspend fun deleteIfOrphaned(photoId: PhotoId): Outcome<Unit, DataError> =
        guard {
            database.transactionWithResult {
                if (queries.selectById(photoId.value).executeAsOneOrNull() == null) {
                    return@transactionWithResult Outcome.Err(DataError.NotFound(photoId.value))
                }
                // No-op when still referenced — the §7 janitor reruns on
                // the 24h tick and will reap once references drop.
                val stillReferenced = queries.selectHasLiveReference(photoId.value).executeAsOneOrNull() != null
                if (!stillReferenced) {
                    queries.softDelete(deleted_at = clock.now(), id = photoId.value)
                }
                Outcome.Ok(Unit)
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> guard(block: () -> Outcome<T, DataError>): Outcome<T, DataError> =
        try {
            block()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Outcome.Err(DataError.Storage(e))
        }
}
