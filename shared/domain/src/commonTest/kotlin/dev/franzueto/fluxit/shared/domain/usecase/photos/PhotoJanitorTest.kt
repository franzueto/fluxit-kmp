package dev.franzueto.fluxit.shared.domain.usecase.photos

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.domain.port.FakePhotoStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoJanitorTest {
    @Test
    fun reclaims_an_orphaned_photo_row_and_file() =
        runTest {
            val storage = FakePhotoStorage()
            val photos = newPhotosRepo(storage, isReferenced = { false })
            val id = (photos.ingest(byteArrayOf(1, 2), "image/jpeg", 10, 10) as Outcome.Ok).value
            assertEquals(1, storage.storedPaths.size)

            assertEquals(Outcome.Ok(true), PhotoJanitor(photos, storage)(id))
            assertTrue(storage.storedPaths.isEmpty())
            assertNull(photos.observe(id).first())
        }

    @Test
    fun a_still_referenced_photo_is_kept() =
        runTest {
            val storage = FakePhotoStorage()
            val photos = newPhotosRepo(storage, isReferenced = { true })
            val id = (photos.ingest(byteArrayOf(1, 2), "image/jpeg", 10, 10) as Outcome.Ok).value

            assertEquals(Outcome.Ok(false), PhotoJanitor(photos, storage)(id))
            assertEquals(1, storage.storedPaths.size)
            assertEquals(id, photos.observe(id).first()!!.id)
        }

    @Test
    fun an_already_gone_photo_is_a_noop() =
        runTest {
            val storage = FakePhotoStorage()
            val photos = newPhotosRepo(storage)
            assertEquals(Outcome.Ok(false), PhotoJanitor(photos, storage)(PhotoId("photo-99999999")))
        }
}
