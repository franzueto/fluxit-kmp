package dev.franzueto.fluxit.shared.state.store

import app.cash.turbine.test
import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.Item
import dev.franzueto.fluxit.shared.domain.model.ItemDraft
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ItemPatch
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.port.AppLogger
import dev.franzueto.fluxit.shared.domain.port.CaptureError
import dev.franzueto.fluxit.shared.domain.port.FakeClock
import dev.franzueto.fluxit.shared.domain.port.FakePhotoCapture
import dev.franzueto.fluxit.shared.domain.port.FakePhotoStorage
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import dev.franzueto.fluxit.shared.domain.repository.FakeItemsRepository
import dev.franzueto.fluxit.shared.domain.repository.FakePhotosRepository
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository
import dev.franzueto.fluxit.shared.domain.usecase.items.DeleteItem
import dev.franzueto.fluxit.shared.domain.usecase.items.ObserveItem
import dev.franzueto.fluxit.shared.domain.usecase.items.UpdateItemDetails
import dev.franzueto.fluxit.shared.domain.usecase.photos.AttachPhotoToItem
import dev.franzueto.fluxit.shared.domain.usecase.photos.DetachPhotoFromItem
import dev.franzueto.fluxit.shared.domain.usecase.photos.PhotoJanitor
import dev.franzueto.fluxit.shared.domain.usecase.photos.ResolvePhotoUri
import dev.franzueto.fluxit.shared.state.testing.StoreTestEnv
import dev.franzueto.fluxit.shared.state.testing.runStoreTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun itemIdSeq(prefix: String): IdGenerator {
    var n = 0
    return IdGenerator { "$prefix-${(++n).toString().padStart(8, '0')}" }
}

private class ItemDetailFix(
    val items: FakeItemsRepository,
    val capture: FakePhotoCapture,
    val store: ItemDetailStore,
    val listId: ListId,
) {
    suspend fun seedItem(title: String = "Milk"): ItemId = (items.add(listId, ItemDraft(title = title)) as Outcome.Ok).value
}

private fun StoreTestEnv.itemDetailFix(): ItemDetailFix {
    val domainClock = FakeClock(Instant.fromEpochSeconds(1_700_000_000))
    val items = FakeItemsRepository(ids = itemIdSeq("item"), clock = domainClock)
    val storage = FakePhotoStorage()
    val photos = FakePhotosRepository(storage = storage, ids = itemIdSeq("photo"), clock = domainClock)
    val capture = FakePhotoCapture()
    val updateItemDetails = UpdateItemDetails(items)
    val store =
        ItemDetailStore(
            scope = scope,
            logger = AppLogger.NoOp,
            observeItem = ObserveItem(items),
            resolvePhotoUri = ResolvePhotoUri(photos, storage),
            updateItemDetails = updateItemDetails,
            deleteItem = DeleteItem(items),
            attachPhotoToItem = AttachPhotoToItem(photos, capture, updateItemDetails),
            detachPhotoFromItem = DetachPhotoFromItem(items, updateItemDetails, PhotoJanitor(photos, storage)),
        )
    return ItemDetailFix(items, capture, store, ListId("list-00000001"))
}

/** Delegating [ItemsRepository] that can inject `update`/`delete` failures. */
private class FailableItemsRepo(
    private val delegate: ItemsRepository,
    var failUpdateWith: DataError? = null,
    var failDeleteWith: DataError? = null,
) : ItemsRepository by delegate {
    override suspend fun update(
        itemId: ItemId,
        patch: ItemPatch,
    ): Outcome<Unit, DataError> = failUpdateWith?.let { Outcome.Err(it) } ?: delegate.update(itemId, patch)

    override suspend fun delete(itemId: ItemId): Outcome<Unit, DataError> =
        failDeleteWith?.let { Outcome.Err(it) } ?: delegate.delete(itemId)
}

private class ItemDetailFailFix(
    val items: FailableItemsRepo,
    val store: ItemDetailStore,
    val listId: ListId,
) {
    suspend fun seedItem(title: String = "Milk"): ItemId = (items.add(listId, ItemDraft(title = title)) as Outcome.Ok).value
}

private fun StoreTestEnv.itemDetailFailFix(): ItemDetailFailFix {
    val domainClock = FakeClock(Instant.fromEpochSeconds(1_700_000_000))
    val items = FailableItemsRepo(FakeItemsRepository(ids = itemIdSeq("item"), clock = domainClock))
    val storage = FakePhotoStorage()
    val photos = FakePhotosRepository(storage = storage, ids = itemIdSeq("photo"), clock = domainClock)
    val capture = FakePhotoCapture()
    val updateItemDetails = UpdateItemDetails(items)
    val store =
        ItemDetailStore(
            scope = scope,
            logger = AppLogger.NoOp,
            observeItem = ObserveItem(items),
            resolvePhotoUri = ResolvePhotoUri(photos, storage),
            updateItemDetails = updateItemDetails,
            deleteItem = DeleteItem(items),
            attachPhotoToItem = AttachPhotoToItem(photos, capture, updateItemDetails),
            detachPhotoFromItem = DetachPhotoFromItem(items, updateItemDetails, PhotoJanitor(photos, storage)),
        )
    return ItemDetailFailFix(items, store, ListId("list-00000001"))
}

@OptIn(ExperimentalCoroutinesApi::class)
class ItemDetailStoreTest {
    @Test
    fun init_loads_the_item_and_syncs_the_working_copy() =
        runStoreTest {
            val f = itemDetailFix()
            val id = f.seedItem("Milk")
            f.store.dispatch(ItemDetailIntent.Init(id))
            testScope.runCurrent()
            assertIs<LoadState.Loaded<Item>>(f.store.state.value.item)
            assertEquals("Milk", f.store.state.value.editing.title)
            assertEquals(PhotoStatus.None, f.store.state.value.photoStatus)
        }

    @Test
    fun title_edit_sets_dirty_and_back_requests_discard_confirm() =
        runStoreTest {
            val f = itemDetailFix()
            val id = f.seedItem()
            f.store.dispatch(ItemDetailIntent.Init(id))
            testScope.runCurrent()
            f.store.dispatch(ItemDetailIntent.TitleChanged("Bread"))
            testScope.runCurrent()
            assertTrue(f.store.state.value.dirty)
            f.store.effects.test {
                f.store.dispatch(ItemDetailIntent.BackClicked)
                assertIs<ItemDetailEffect.ConfirmDiscardChanges>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun save_persists_edits_clears_dirty_and_navigates_back() =
        runStoreTest {
            val f = itemDetailFix()
            val id = f.seedItem("Milk")
            f.store.dispatch(ItemDetailIntent.Init(id))
            testScope.runCurrent()
            f.store.dispatch(ItemDetailIntent.TitleChanged("Bread"))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(ItemDetailIntent.SaveClicked)
                assertIs<ItemDetailEffect.NavigateBack>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(false, f.store.state.value.dirty)
            assertEquals(
                "Bread",
                f.items
                    .observe(id)
                    .first()
                    ?.title,
            )
        }

    @Test
    fun back_without_edits_navigates_directly() =
        runStoreTest {
            val f = itemDetailFix()
            val id = f.seedItem()
            f.store.dispatch(ItemDetailIntent.Init(id))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(ItemDetailIntent.BackClicked)
                assertIs<ItemDetailEffect.NavigateBack>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun attach_photo_from_camera_ends_in_loaded() =
        runStoreTest {
            val f = itemDetailFix()
            val id = f.seedItem()
            f.store.dispatch(ItemDetailIntent.Init(id))
            testScope.runCurrent()
            f.store.dispatch(ItemDetailIntent.UpdatePhotoClicked)
            testScope.runCurrent()
            assertTrue(f.store.state.value.showPhotoSourceSheet)
            f.store.dispatch(ItemDetailIntent.PhotoSourceSelected(PhotoPickSource.Camera))
            testScope.runCurrent()
            assertEquals(false, f.store.state.value.showPhotoSourceSheet)
            assertEquals(1, f.capture.captureCalls)
            assertIs<PhotoStatus.Loaded>(f.store.state.value.photoStatus)
        }

    @Test
    fun user_cancelled_capture_is_a_quiet_abort() =
        runStoreTest {
            val f = itemDetailFix()
            f.capture.captureResult = Outcome.Err(CaptureError.UserCancelled)
            val id = f.seedItem()
            f.store.dispatch(ItemDetailIntent.Init(id))
            testScope.runCurrent()
            f.store.dispatch(ItemDetailIntent.PhotoSourceSelected(PhotoPickSource.Camera))
            testScope.runCurrent()
            // Restored to the pre-capture status (None), no error state.
            assertEquals(PhotoStatus.None, f.store.state.value.photoStatus)
        }

    @Test
    fun permission_denied_emits_request_permission() =
        runStoreTest {
            val f = itemDetailFix()
            f.capture.libraryResult = Outcome.Err(CaptureError.PermissionDenied)
            val id = f.seedItem()
            f.store.dispatch(ItemDetailIntent.Init(id))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(ItemDetailIntent.PhotoSourceSelected(PhotoPickSource.Library))
                assertIs<ItemDetailEffect.RequestPhotoLibraryAccess>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun confirm_delete_deletes_and_navigates_back() =
        runStoreTest {
            val f = itemDetailFix()
            val id = f.seedItem()
            f.store.dispatch(ItemDetailIntent.Init(id))
            testScope.runCurrent()
            f.store.dispatch(ItemDetailIntent.DeleteClicked)
            testScope.runCurrent()
            assertTrue(f.store.state.value.confirmDelete)
            f.store.effects.test {
                f.store.dispatch(ItemDetailIntent.ConfirmDelete)
                assertIs<ItemDetailEffect.NavigateBack>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun description_change_sets_dirty_and_cancel_delete_clears_the_flag() =
        runStoreTest {
            val f = itemDetailFix()
            val id = f.seedItem()
            f.store.dispatch(ItemDetailIntent.Init(id))
            testScope.runCurrent()
            f.store.dispatch(ItemDetailIntent.DescriptionChanged("buy 2%"))
            testScope.runCurrent()
            assertTrue(f.store.state.value.dirty)
            assertEquals("buy 2%", f.store.state.value.editing.description)
            f.store.dispatch(ItemDetailIntent.DeleteClicked)
            testScope.runCurrent()
            assertTrue(f.store.state.value.confirmDelete)
            f.store.dispatch(ItemDetailIntent.CancelDelete)
            testScope.runCurrent()
            assertEquals(false, f.store.state.value.confirmDelete)
        }

    @Test
    fun remove_photo_detaches_and_resolves_back_to_none() =
        runStoreTest {
            val f = itemDetailFix()
            val id = f.seedItem()
            f.store.dispatch(ItemDetailIntent.Init(id))
            testScope.runCurrent()
            // Attach a photo first so there is something to remove.
            f.store.dispatch(ItemDetailIntent.PhotoSourceSelected(PhotoPickSource.Camera))
            testScope.runCurrent()
            assertIs<PhotoStatus.Loaded>(f.store.state.value.photoStatus)
            // Remove it → detach succeeds, the item re-emits with photoId = null → None.
            f.store.dispatch(ItemDetailIntent.RemovePhotoClicked)
            testScope.runCurrent()
            assertEquals(PhotoStatus.None, f.store.state.value.photoStatus)
        }

    @Test
    fun remove_photo_before_init_is_a_noop() =
        runStoreTest {
            val f = itemDetailFix()
            f.store.effects.test {
                f.store.dispatch(ItemDetailIntent.RemovePhotoClicked)
                testScope.runCurrent()
                expectNoEvents()
            }
        }

    @Test
    fun save_before_init_is_a_noop() =
        runStoreTest {
            val f = itemDetailFix()
            f.store.effects.test {
                f.store.dispatch(ItemDetailIntent.SaveClicked)
                testScope.runCurrent()
                expectNoEvents()
            }
        }

    @Test
    fun a_second_init_is_ignored() =
        runStoreTest {
            val f = itemDetailFix()
            val id = f.seedItem("Milk")
            f.store.dispatch(ItemDetailIntent.Init(id))
            testScope.runCurrent()
            f.store.dispatch(ItemDetailIntent.Init(id))
            testScope.runCurrent()
            assertIs<LoadState.Loaded<Item>>(f.store.state.value.item)
        }

    @Test
    fun camera_permission_denied_emits_request_camera() =
        runStoreTest {
            val f = itemDetailFix()
            f.capture.captureResult = Outcome.Err(CaptureError.PermissionDenied)
            val id = f.seedItem()
            f.store.dispatch(ItemDetailIntent.Init(id))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(ItemDetailIntent.PhotoSourceSelected(PhotoPickSource.Camera))
                assertIs<ItemDetailEffect.RequestCameraPermission>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun unknown_capture_error_sets_error_status_and_shows_error() =
        runStoreTest {
            val f = itemDetailFix()
            f.capture.captureResult = Outcome.Err(CaptureError.Unknown(RuntimeException("boom")))
            val id = f.seedItem()
            f.store.dispatch(ItemDetailIntent.Init(id))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(ItemDetailIntent.PhotoSourceSelected(PhotoPickSource.Camera))
                assertIs<ItemDetailEffect.ShowError>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(PhotoStatus.Error, f.store.state.value.photoStatus)
        }

    @Test
    fun save_failure_shows_error() =
        runStoreTest {
            val f = itemDetailFailFix()
            val id = f.seedItem("Milk")
            f.store.dispatch(ItemDetailIntent.Init(id))
            testScope.runCurrent()
            f.store.dispatch(ItemDetailIntent.TitleChanged("Bread"))
            testScope.runCurrent()
            f.items.failUpdateWith = DataError.Storage(RuntimeException("disk full"))
            f.store.effects.test {
                f.store.dispatch(ItemDetailIntent.SaveClicked)
                assertIs<ItemDetailEffect.ShowError>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            // Save failed → still dirty.
            assertTrue(f.store.state.value.dirty)
        }

    @Test
    fun confirm_delete_failure_shows_error_and_clears_confirm() =
        runStoreTest {
            val f = itemDetailFailFix()
            val id = f.seedItem()
            f.store.dispatch(ItemDetailIntent.Init(id))
            testScope.runCurrent()
            f.items.failDeleteWith = DataError.Storage(RuntimeException("nope"))
            f.store.dispatch(ItemDetailIntent.DeleteClicked)
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(ItemDetailIntent.ConfirmDelete)
                assertIs<ItemDetailEffect.ShowError>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(false, f.store.state.value.confirmDelete)
        }

    @Test
    fun attach_failure_from_ingest_surfaces_error() =
        runStoreTest {
            val f = itemDetailFailFix()
            val id = f.seedItem()
            f.store.dispatch(ItemDetailIntent.Init(id))
            testScope.runCurrent()
            // Capture succeeds (default), but the attach's persistence step fails →
            // a non-capture DomainError → handleAttachError's `else` (reason == null).
            f.items.failUpdateWith = DataError.Storage(RuntimeException("disk full"))
            f.store.effects.test {
                f.store.dispatch(ItemDetailIntent.PhotoSourceSelected(PhotoPickSource.Camera))
                assertIs<ItemDetailEffect.ShowError>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(PhotoStatus.Error, f.store.state.value.photoStatus)
        }
}
