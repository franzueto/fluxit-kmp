package dev.franzueto.fluxit.shared.domain.usecase.photos

import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.domain.port.CaptureError
import dev.franzueto.fluxit.shared.domain.port.FakePhotoCapture
import dev.franzueto.fluxit.shared.domain.port.FakePhotoStorage
import dev.franzueto.fluxit.shared.domain.repository.FakeItemsRepository
import dev.franzueto.fluxit.shared.domain.usecase.items.UpdateItemDetails
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachPhotoToItemTest {
    private suspend fun seedItem(items: FakeItemsRepository): ItemId = (items.add(sampleListId, itemDraft()) as Outcome.Ok).value

    @Test
    fun camera_capture_ingests_and_points_the_item_at_the_new_photo() =
        runTest {
            val storage = FakePhotoStorage()
            val photos = newPhotosRepo(storage)
            val items = newItemsRepo()
            val capture = FakePhotoCapture()
            val id = seedItem(items)

            val attach = AttachPhotoToItem(photos, capture, UpdateItemDetails(items))
            val photoId = assertIs<Outcome.Ok<PhotoId>>(attach(id, PhotoSource.CAMERA)).value

            assertEquals(photoId, items.observe(id).first()!!.photoId)
            assertEquals(1, storage.storedPaths.size)
            assertEquals(1, capture.captureCalls)
            assertEquals(0, capture.libraryCalls)
        }

    @Test
    fun library_source_opens_the_picker() =
        runTest {
            val storage = FakePhotoStorage()
            val photos = newPhotosRepo(storage)
            val items = newItemsRepo()
            val capture = FakePhotoCapture()
            val id = seedItem(items)

            AttachPhotoToItem(photos, capture, UpdateItemDetails(items))(id, PhotoSource.LIBRARY)

            assertEquals(0, capture.captureCalls)
            assertEquals(1, capture.libraryCalls)
        }

    @Test
    fun capture_failure_surfaces_capture_failure_with_no_side_effects() =
        runTest {
            val storage = FakePhotoStorage()
            val photos = newPhotosRepo(storage)
            val items = newItemsRepo()
            val capture = FakePhotoCapture(captureResult = Outcome.Err(CaptureError.UserCancelled))
            val id = seedItem(items)

            val err =
                assertIs<Outcome.Err<DomainError>>(
                    AttachPhotoToItem(photos, capture, UpdateItemDetails(items))(id, PhotoSource.CAMERA),
                )
            assertEquals(DomainError.CaptureFailure(reason = CaptureError.UserCancelled), err.error)
            assertTrue(storage.storedPaths.isEmpty())
            assertNull(items.observe(id).first()!!.photoId)
        }

    @Test
    fun ingest_failure_surfaces_storage_failure_and_leaves_the_item_untouched() =
        runTest {
            val storage = FakePhotoStorage()
            val photos = newPhotosRepo(storage)
            photos.failIngestWith = DataError.Storage(RuntimeException("disk full"))
            val items = newItemsRepo()
            val capture = FakePhotoCapture()
            val id = seedItem(items)

            val err =
                assertIs<Outcome.Err<DomainError>>(
                    AttachPhotoToItem(photos, capture, UpdateItemDetails(items))(id, PhotoSource.CAMERA),
                )
            assertIs<DomainError.StorageFailure>(err.error)
            assertNull(items.observe(id).first()!!.photoId)
        }

    @Test
    fun missing_item_is_domain_not_found() =
        runTest {
            val storage = FakePhotoStorage()
            val photos = newPhotosRepo(storage)
            val items = newItemsRepo()
            val capture = FakePhotoCapture()

            val err =
                assertIs<Outcome.Err<DomainError>>(
                    AttachPhotoToItem(photos, capture, UpdateItemDetails(items))(ItemId("item-99999999"), PhotoSource.CAMERA),
                )
            assertEquals(DomainError.NotFound(entity = "Item", id = "item-99999999"), err.error)
        }
}
