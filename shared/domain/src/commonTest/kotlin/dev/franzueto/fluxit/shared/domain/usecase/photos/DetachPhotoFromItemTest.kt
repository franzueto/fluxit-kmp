package dev.franzueto.fluxit.shared.domain.usecase.photos

import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Optional
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.domain.port.FakePhotoStorage
import dev.franzueto.fluxit.shared.domain.repository.FakeItemsRepository
import dev.franzueto.fluxit.shared.domain.repository.FakePhotosRepository
import dev.franzueto.fluxit.shared.domain.usecase.items.UpdateItemDetails
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetachPhotoFromItemTest {
    private suspend fun seedItemWithPhoto(
        items: FakeItemsRepository,
        photos: FakePhotosRepository,
    ): Pair<ItemId, PhotoId> {
        val itemId = (items.add(sampleListId, itemDraft()) as Outcome.Ok).value
        val photoId = (photos.ingest(byteArrayOf(1, 2), "image/jpeg", 10, 10) as Outcome.Ok).value
        UpdateItemDetails(items)(itemId, photoId = Optional.Set(photoId))
        return itemId to photoId
    }

    @Test
    fun clears_the_item_photo_and_reclaims_the_orphaned_file() =
        runTest {
            val storage = FakePhotoStorage()
            val photos = newPhotosRepo(storage, isReferenced = { false })
            val items = newItemsRepo()
            val (itemId, photoId) = seedItemWithPhoto(items, photos)

            val detach = DetachPhotoFromItem(items, UpdateItemDetails(items), PhotoJanitor(photos, storage))
            assertEquals(Outcome.Ok(Unit), detach(itemId))

            assertNull(items.observe(itemId).first()!!.photoId)
            assertNull(photos.observe(photoId).first())
            assertTrue(storage.storedPaths.isEmpty())
        }

    @Test
    fun a_photo_still_referenced_elsewhere_survives_the_detach() =
        runTest {
            val storage = FakePhotoStorage()
            val photos = newPhotosRepo(storage, isReferenced = { true })
            val items = newItemsRepo()
            val (itemId, photoId) = seedItemWithPhoto(items, photos)

            val detach = DetachPhotoFromItem(items, UpdateItemDetails(items), PhotoJanitor(photos, storage))
            assertEquals(Outcome.Ok(Unit), detach(itemId))

            assertNull(items.observe(itemId).first()!!.photoId)
            assertEquals(photoId, photos.observe(photoId).first()!!.id)
            assertEquals(1, storage.storedPaths.size)
        }

    @Test
    fun an_item_with_no_photo_is_a_noop() =
        runTest {
            val storage = FakePhotoStorage()
            val photos = newPhotosRepo(storage)
            val items = newItemsRepo()
            val itemId = (items.add(sampleListId, itemDraft()) as Outcome.Ok).value

            val detach = DetachPhotoFromItem(items, UpdateItemDetails(items), PhotoJanitor(photos, storage))
            assertEquals(Outcome.Ok(Unit), detach(itemId))
        }

    @Test
    fun a_failure_clearing_the_item_pointer_surfaces_the_error() =
        runTest {
            val storage = FakePhotoStorage()
            val photos = newPhotosRepo(storage)
            val items = newItemsRepo()
            val (itemId, _) = seedItemWithPhoto(items, photos)
            items.failUpdateWith = DataError.Storage(RuntimeException("disk full"))

            val detach = DetachPhotoFromItem(items, UpdateItemDetails(items), PhotoJanitor(photos, storage))
            val err = assertIs<Outcome.Err<DomainError>>(detach(itemId))
            assertIs<DomainError.StorageFailure>(err.error)
        }

    @Test
    fun a_janitor_failure_after_clearing_surfaces_the_error() =
        runTest {
            val storage = FakePhotoStorage()
            val photos = newPhotosRepo(storage)
            val items = newItemsRepo()
            val (itemId, _) = seedItemWithPhoto(items, photos)
            // The clear succeeds; the orphan sweep then fails.
            photos.failDeleteIfOrphanedWith = DataError.Storage(RuntimeException("disk full"))

            val detach = DetachPhotoFromItem(items, UpdateItemDetails(items), PhotoJanitor(photos, storage))
            val err = assertIs<Outcome.Err<DomainError>>(detach(itemId))
            assertIs<DomainError.StorageFailure>(err.error)
            // The item pointer was cleared before the janitor ran.
            assertNull(items.observe(itemId).first()!!.photoId)
        }

    @Test
    fun missing_item_is_domain_not_found() =
        runTest {
            val storage = FakePhotoStorage()
            val photos = newPhotosRepo(storage)
            val items = newItemsRepo()

            val detach = DetachPhotoFromItem(items, UpdateItemDetails(items), PhotoJanitor(photos, storage))
            val err = assertIs<Outcome.Err<DomainError>>(detach(ItemId("item-99999999")))
            assertEquals(DomainError.NotFound(entity = "Item", id = "item-99999999"), err.error)
        }
}
