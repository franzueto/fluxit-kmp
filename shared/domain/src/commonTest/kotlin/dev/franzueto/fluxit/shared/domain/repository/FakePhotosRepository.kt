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
 * In-memory [PhotosRepository] for §7 use-case tests (Phase 04 §11).
 * Honors the §7 file-first-then-row contract: `ingest` writes via the
 * injected [PhotoStorage] first; only on success does the row land in
 * the state flow. The fake doesn't simulate ingest failure (real
 * failures arrive via storage IO), so the cleanup path of "row insert
 * failed → delete file" is exercised at the `SqlPhotosRepository`
 * layer in `:shared:data`'s integration tests, not here.
 *
 * `deleteIfOrphaned` consults the injected [isReferenced] callback to
 * decide whether the photo is still in use by a live item. Default is
 * `{ false }` — treat every photo as orphaned, which is fine for
 * standalone repo tests. Use-case wiring (e.g. `PhotoJanitor`) passes
 * a real check against [FakeItemsRepository]'s state.
 *
 * The method **does not** hard-delete the file. Per the §7 contract
 * (and the spec on `deleteIfOrphaned`), file removal + row hard-delete
 * is the `PhotoJanitor` use case's job; this method only soft-deletes
 * the row.
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

    /**
     * Controllable failure modes (Phase 04 §11 "fakes with controllable
     * failure modes"). When non-null, the corresponding write short-circuits
     * with the given [DataError] before touching state — used by use-case
     * tests to drive the `mapError { it.toDomain(...) }` lift branches that a
     * happy-path fake can't reach (e.g. `AttachPhotoToItem`'s ingest-failure
     * path, `PhotoJanitor`'s orphan-sweep-failure path).
     */
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
