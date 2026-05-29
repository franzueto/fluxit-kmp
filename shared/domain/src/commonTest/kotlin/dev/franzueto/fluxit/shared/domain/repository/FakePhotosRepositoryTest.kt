package dev.franzueto.fluxit.shared.domain.repository

import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.domain.port.FakeClock
import dev.franzueto.fluxit.shared.domain.port.FakePhotoStorage
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakePhotosRepositoryTest {
    private val epoch = Instant.fromEpochSeconds(1_700_000_000)

    private fun seqIds(): IdGenerator {
        var n = 0
        return IdGenerator {
            n++
            "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
        }
    }

    private fun newSetup(isReferenced: (PhotoId) -> Boolean = { false }): Triple<FakePhotosRepository, FakePhotoStorage, FakeClock> {
        val storage = FakePhotoStorage()
        val clock = FakeClock(epoch)
        val repo =
            FakePhotosRepository(
                storage = storage,
                ids = seqIds(),
                clock = clock,
                isReferenced = isReferenced,
            )
        return Triple(repo, storage, clock)
    }

    @Test
    fun ingest_writes_file_then_row_observable() =
        runTest {
            val (repo, storage, _) = newSetup()
            val bytes = ByteArray(128) { it.toByte() }
            val id = (repo.ingest(bytes, mime = "image/jpeg", width = 800, height = 600) as Outcome.Ok).value
            assertEquals(1, storage.storedPaths.size)
            val photo = repo.observe(id).first()
            assertNotNull(photo)
            assertEquals(id, photo!!.id)
            assertEquals("image/jpeg", photo.mimeType)
            assertEquals(800, photo.widthPx)
            assertEquals(600, photo.heightPx)
            assertEquals(128L, photo.byteSize)
            assertTrue(storage.exists(photo.relativePath))
        }

    @Test
    fun ingest_mints_distinct_ids_and_paths_for_repeated_calls() =
        runTest {
            val (repo, storage, _) = newSetup()
            val a = (repo.ingest(ByteArray(1), "image/jpeg", 1, 1) as Outcome.Ok).value
            val b = (repo.ingest(ByteArray(1), "image/jpeg", 1, 1) as Outcome.Ok).value
            assertTrue(a != b)
            assertEquals(2, storage.storedPaths.size)
        }

    @Test
    fun observe_emits_null_for_unknown_id() =
        runTest {
            val (repo, _, _) = newSetup()
            assertNull(repo.observe(PhotoId("00000000-0000-4000-8000-aaaaaaaaaaaa")).first())
        }

    @Test
    fun delete_if_orphaned_soft_deletes_when_unreferenced() =
        runTest {
            val (repo, storage, _) = newSetup() // default isReferenced = false
            val id = (repo.ingest(ByteArray(1), "image/jpeg", 1, 1) as Outcome.Ok).value
            assertEquals(Outcome.Ok(Unit), repo.deleteIfOrphaned(id))
            // Row tombstoned (observe returns null) but file is still there —
            // hard-delete + file cleanup is the PhotoJanitor's job.
            assertNull(repo.observe(id).first())
            assertEquals(1, storage.storedPaths.size)
        }

    @Test
    fun delete_if_orphaned_is_noop_when_still_referenced() =
        runTest {
            // Always-referenced — deleteIfOrphaned should return Ok without
            // tombstoning the row.
            val (repo, _, _) = newSetup(isReferenced = { true })
            val id = (repo.ingest(ByteArray(1), "image/jpeg", 1, 1) as Outcome.Ok).value
            assertEquals(Outcome.Ok(Unit), repo.deleteIfOrphaned(id))
            // Photo still observable — proves the no-op branch took effect.
            assertNotNull(repo.observe(id).first())
        }

    @Test
    fun delete_if_orphaned_returns_not_found_for_missing_id() =
        runTest {
            val (repo, _, _) = newSetup()
            val bogus = PhotoId("00000000-0000-4000-8000-bbbbbbbbbbbb")
            val err = assertIs<Outcome.Err<DataError>>(repo.deleteIfOrphaned(bogus))
            assertEquals(DataError.NotFound(bogus.value), err.error)
        }
}
