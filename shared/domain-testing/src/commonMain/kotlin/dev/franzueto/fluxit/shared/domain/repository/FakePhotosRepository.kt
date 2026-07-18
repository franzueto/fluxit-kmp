package dev.franzueto.fluxit.shared.domain.repository

import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.Photo
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.domain.port.Clock
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import dev.franzueto.fluxit.shared.domain.port.PhotoStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

/**
 * `deleteIfOrphaned` consults the injected [isReferenced] callback to
 * decide whether the photo is still in use by a live item. Default is
 * `{ false }` — treat every photo as orphaned, which is fine for
 * standalone repo tests. Use-case wiring (e.g. `PhotoJanitor`) passes
 * a real check against [FakeItemsRepository]'s state.
 */
public class FakePhotosRepository(
    private val storage: PhotoStorage,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val isReferenced: (PhotoId) -> Boolean = { false },
) : PhotosRepository {
    private data class Row(
        val id: PhotoId,
        val relativePath: String,
        val mimeType: String,
        val widthPx: Int,
        val heightPx: Int,
        val byteSize: Long,
        val createdAt: Instant,
        val deletedAt: Instant?,
    )

    private val state = MutableStateFlow<List<Row>>(emptyList())

    public var failIngestWith: DataError? = null
    public var failDeleteIfOrphanedWith: DataError? = null

    // ── reads ────────────────────────────────────────────────────────────

    override fun observe(photoId: PhotoId): Flow<Photo?> =
        state.map { rows ->
            rows.firstOrNull { it.id == photoId && it.deletedAt == null }?.toPhoto()
        }

    // ── writes ───────────────────────────────────────────────────────────

    override suspend fun ingest(
        bytes: ByteArray,
        mime: String,
        width: Int,
        height: Int,
    ): Outcome<PhotoId, DataError> {
        failIngestWith?.let { return Outcome.Err(it) }
        val now = clock.now()
        val path = storage.write(bytes, mime)
        val id = PhotoId(ids.newId())
        val row =
            Row(
                id = id,
                relativePath = path,
                mimeType = mime,
                widthPx = width,
                heightPx = height,
                byteSize = bytes.size.toLong(),
                createdAt = now,
                deletedAt = null,
            )
        state.value = state.value + row
        return Outcome.Ok(id)
    }

    override suspend fun deleteIfOrphaned(photoId: PhotoId): Outcome<Unit, DataError> {
        failDeleteIfOrphanedWith?.let { return Outcome.Err(it) }
        val current = state.value
        if (current.none { it.id == photoId && it.deletedAt == null }) {
            return Outcome.Err(DataError.NotFound(photoId.value))
        }
        if (isReferenced(photoId)) {
            // Still referenced — no-op (matches the production contract).
            return Outcome.Ok(Unit)
        }
        val now = clock.now()
        state.value =
            current.map { r ->
                if (r.id == photoId && r.deletedAt == null) r.copy(deletedAt = now) else r
            }
        return Outcome.Ok(Unit)
    }

    private fun Row.toPhoto(): Photo =
        Photo(
            id = id,
            relativePath = relativePath,
            mimeType = mimeType,
            widthPx = widthPx,
            heightPx = heightPx,
            byteSize = byteSize,
            createdAt = createdAt,
        )
}
