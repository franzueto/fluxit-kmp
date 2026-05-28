package dev.franzueto.fluxit.shared.data.repository

import app.cash.turbine.test
import dev.franzueto.fluxit.core.utils.IdGenerator
import dev.franzueto.fluxit.shared.data.db.FluxItDatabase
import dev.franzueto.fluxit.shared.data.db.fluxItDatabase
import dev.franzueto.fluxit.shared.data.db.inMemoryDriver
import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ItemDraft
import dev.franzueto.fluxit.shared.domain.model.ItemPatch
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.domain.port.PhotoStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlPhotosRepositorySmokeTest {
    private val fixedNow = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val fakeClock =
        object : Clock {
            override fun now(): Instant = fixedNow
        }

    private class FakePhotoStorage : PhotoStorage {
        val files = mutableMapOf<String, ByteArray>()
        var pathCounter = 0
        val deletedPaths = mutableListOf<String>()

        override suspend fun write(
            bytes: ByteArray,
            mime: String,
        ): String {
            pathCounter += 1
            val path = "photos/$pathCounter.jpg"
            files[path] = bytes
            return path
        }

        override suspend fun read(relativePath: String): ByteArray? = files[relativePath]

        override suspend fun delete(relativePath: String): Boolean {
            deletedPaths += relativePath
            return files.remove(relativePath) != null
        }

        override fun resolveAbsolute(relativePath: String): String = "/sandbox/$relativePath"
    }

    private data class Fixture(
        val db: FluxItDatabase,
        val storage: FakePhotoStorage,
        val photos: SqlPhotosRepository,
        val lists: SqlListsRepository,
        val items: SqlItemsRepository,
    )

    private fun fixture(
        seed: Long = 0L,
        idsOverride: IdGenerator? = null,
    ): Fixture {
        var n = seed
        val gen =
            idsOverride ?: IdGenerator {
                n += 1
                "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
            }
        val db = fluxItDatabase(inMemoryDriver())
        val storage = FakePhotoStorage()
        return Fixture(
            db = db,
            storage = storage,
            photos =
                SqlPhotosRepository(
                    database = db,
                    storage = storage,
                    clock = fakeClock,
                    ids = gen,
                    dispatcher = Dispatchers.Unconfined,
                ),
            lists =
                SqlListsRepository(
                    database = db,
                    clock = fakeClock,
                    ids = gen,
                    dispatcher = Dispatchers.Unconfined,
                ),
            items =
                SqlItemsRepository(
                    database = db,
                    clock = fakeClock,
                    ids = gen,
                    dispatcher = Dispatchers.Unconfined,
                ),
        )
    }

    @Test
    fun ingest_writes_file_then_inserts_row_and_emits_via_observe() =
        runTest {
            val fx = fixture()
            val bytes = ByteArray(128) { it.toByte() }
            val id = (fx.photos.ingest(bytes, "image/jpeg", 1024, 768) as Outcome.Ok).value
            fx.photos.observe(id).test {
                val row = awaitItem()!!
                assertEquals(1024, row.widthPx)
                assertEquals(768, row.heightPx)
                assertEquals(128L, row.byteSize)
                assertEquals("image/jpeg", row.mimeType)
                assertTrue(row.relativePath in fx.storage.files)
            }
        }

    @Test
    fun ingest_cleans_up_file_when_row_insert_fails() =
        runTest {
            // Pinning the IdGenerator to a constant value forces the second
            // ingest() to collide on the PK — the row insert throws, and the
            // repo must delete the freshly-written file before returning.
            val fx =
                fixture(
                    idsOverride = IdGenerator { "00000000-0000-4000-8000-cccccccccccc" },
                )
            assertTrue(fx.photos.ingest(byteArrayOf(1), "image/jpeg", 1, 1) is Outcome.Ok)
            val before = fx.storage.files.size
            val out = fx.photos.ingest(byteArrayOf(2), "image/jpeg", 1, 1)
            assertTrue(out is Outcome.Err)
            assertTrue((out as Outcome.Err).error is DataError.Storage)
            // Second write produced a path; cleanup must have removed it.
            assertEquals(before, fx.storage.files.size)
            assertEquals(1, fx.storage.deletedPaths.size)
        }

    @Test
    fun observe_emits_null_after_orphan_deletion() =
        runTest {
            val fx = fixture()
            val id = (fx.photos.ingest(byteArrayOf(1), "image/jpeg", 1, 1) as Outcome.Ok).value
            assertTrue(fx.photos.deleteIfOrphaned(id) is Outcome.Ok)
            fx.photos.observe(id).test { assertNull(awaitItem()) }
        }

    @Test
    fun deleteIfOrphaned_is_a_noop_when_a_live_item_references_the_photo() =
        runTest {
            val fx = fixture()
            val photoId = (fx.photos.ingest(byteArrayOf(1), "image/jpeg", 1, 1) as Outcome.Ok).value
            val listId =
                (
                    fx.lists.create(
                        ListDraft(name = "L", icon = FluxItIconRef.CART, color = ColorToken.PRIMARY_BLUE),
                    ) as Outcome.Ok
                ).value
            val itemId = (fx.items.add(listId, ItemDraft("X")) as Outcome.Ok).value
            // Wire the item to the photo.
            fx.items.update(itemId, ItemPatch(title = "X", subtitle = null, description = null, photoId = photoId))

            val out = fx.photos.deleteIfOrphaned(photoId)
            assertTrue(out is Outcome.Ok)
            // Row still visible — no soft-delete happened.
            fx.photos.observe(photoId).test { assertNotNull(awaitItem()) }
        }

    @Test
    fun deleteIfOrphaned_soft_deletes_after_referencing_item_is_removed() =
        runTest {
            val fx = fixture()
            val photoId = (fx.photos.ingest(byteArrayOf(1), "image/jpeg", 1, 1) as Outcome.Ok).value
            val listId =
                (
                    fx.lists.create(
                        ListDraft(name = "L", icon = FluxItIconRef.CART, color = ColorToken.PRIMARY_BLUE),
                    ) as Outcome.Ok
                ).value
            val itemId = (fx.items.add(listId, ItemDraft("X")) as Outcome.Ok).value
            fx.items.update(itemId, ItemPatch("X", null, null, photoId))
            // Sanity: still referenced, no-op.
            assertTrue(fx.photos.deleteIfOrphaned(photoId) is Outcome.Ok)
            assertFalse(
                fx.db.photosQueries
                    .selectById(photoId.value)
                    .executeAsOneOrNull() == null,
            )

            // Remove the reference and re-run.
            fx.items.delete(itemId)
            assertTrue(fx.photos.deleteIfOrphaned(photoId) is Outcome.Ok)
            fx.photos.observe(photoId).test { assertNull(awaitItem()) }
        }

    @Test
    fun deleteIfOrphaned_returns_not_found_for_unknown_or_tombstoned_id() =
        runTest {
            val fx = fixture()
            val out = fx.photos.deleteIfOrphaned(PhotoId("00000000-0000-4000-8000-deadbeefcafe"))
            assertTrue((out as Outcome.Err).error is DataError.NotFound)
        }
}
