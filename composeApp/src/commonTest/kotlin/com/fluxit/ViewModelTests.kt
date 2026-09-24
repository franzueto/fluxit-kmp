package com.fluxit

import com.fluxit.data.DebugSeeder
import com.fluxit.data.PhotoContent
import com.fluxit.data.PhotoRejected
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.feature.createlist.CreateListViewModel
import com.fluxit.feature.dashboard.DashboardViewModel
import com.fluxit.feature.itemdetail.ItemDetailViewModel
import com.fluxit.feature.itemdetail.PhotoOperationKind
import com.fluxit.feature.listdetail.ListDetailViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var lists: FakeListRepository
    private lateinit var items: FakeItemRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        lists = FakeListRepository()
        items = FakeItemRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = DashboardViewModel(lists, DebugSeeder(lists, items))

    @Test
    fun searchFiltersListsCaseInsensitively() = runTest(dispatcher) {
        lists.createList("Supermarket", ListIcon.CART, ListColor.ORANGE)
        lists.createList("Trip to Japan", ListIcon.TRAVEL, ListColor.PRIMARY_BLUE)
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }

        vm.onSearchChange("japan")
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(1, state.lists.size)
        assertEquals("Trip to Japan", state.lists.first().list.name)
        collectJob.cancel()
    }

    @Test
    fun deleteThenUndoRestoresList() = runTest(dispatcher) {
        val id = lists.createList("Supermarket", ListIcon.CART, ListColor.ORANGE)
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        vm.deleteList(id)
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.uiState.value.lists.isEmpty())
        assertNotNull(vm.undoListId.value)

        vm.undoDelete()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, vm.uiState.value.lists.size)
        collectJob.cancel()
    }

    @Test
    fun undoWindowExpiresAfterFiveSeconds() = runTest(dispatcher) {
        val id = lists.createList("Supermarket", ListIcon.CART, ListColor.ORANGE)
        val vm = viewModel()

        vm.deleteList(id)
        dispatcher.scheduler.runCurrent()
        assertNotNull(vm.undoListId.value)

        dispatcher.scheduler.advanceTimeBy(5_100)
        dispatcher.scheduler.runCurrent()
        assertEquals(null, vm.undoListId.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ListDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var lists: FakeListRepository
    private lateinit var items: FakeItemRepository
    private lateinit var listId: String

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        lists = FakeListRepository()
        items = FakeItemRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun composerSubmitAddsItemAndClearsText() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        val vm = ListDetailViewModel(listId, lists, items)
        val collectJob = launch { vm.uiState.collect {} }

        vm.onComposerChange("Milk")
        vm.submitComposer()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("", vm.uiState.value.composerText)
        assertEquals(listOf("Milk"), vm.uiState.value.activeItems.map { it.title })
        collectJob.cancel()
    }

    @Test
    fun blankComposerIsIgnored() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        val vm = ListDetailViewModel(listId, lists, items)

        vm.onComposerChange("   ")
        vm.submitComposer()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(items.rows.value.isEmpty())
    }

    @Test
    fun toggleMovesItemBetweenSections() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        val vm = ListDetailViewModel(listId, lists, items)
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleCompleted(vm.uiState.value.activeItems.first())
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.activeItems.isEmpty())
        assertEquals(1, vm.uiState.value.completedItems.size)
        assertEquals(1, vm.uiState.value.completedCount)
        collectJob.cancel()
    }

    @Test
    fun clearCompletedSoftDeletesOnlyCompleted() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        items.addItem(listId, "Bread")
        items.setCompleted(listId, items.observeItems(listId).first().first().id, true)
        val vm = ListDetailViewModel(listId, lists, items)
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        vm.clearCompleted()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("Bread"), vm.uiState.value.activeItems.map { it.title })
        assertTrue(vm.uiState.value.completedItems.isEmpty())
        collectJob.cancel()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CreateListViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var lists: FakeListRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        lists = FakeListRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun blankNameIsInvalid() = runTest(dispatcher) {
        val vm = CreateListViewModel(null, lists)
        assertFalse(vm.uiState.value.isValid)
        vm.onNameChange("  ")
        assertFalse(vm.uiState.value.isValid)
        vm.onNameChange("Trip")
        assertTrue(vm.uiState.value.isValid)
    }

    @Test
    fun nameIsCappedAtMaxLength() = runTest(dispatcher) {
        val vm = CreateListViewModel(null, lists)
        vm.onNameChange("a".repeat(61))
        assertEquals("", vm.uiState.value.name)
        vm.onNameChange("a".repeat(60))
        assertEquals(60, vm.uiState.value.name.length)
    }

    @Test
    fun saveCreatesListAndExposesId() = runTest(dispatcher) {
        val vm = CreateListViewModel(null, lists)
        vm.onNameChange("Trip")
        vm.onIconChange(ListIcon.TRAVEL)
        vm.onColorChange(ListColor.SKY)
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        val savedId = vm.uiState.value.savedListId
        assertNotNull(savedId)
        val created = lists.observeList(savedId).first()
        assertNotNull(created)
        assertEquals("Trip", created.name)
        assertEquals(ListIcon.TRAVEL, created.icon)
        assertEquals(ListColor.SKY, created.color)
    }

    @Test
    fun editModeLoadsExistingValuesAndTracksDirty() = runTest(dispatcher) {
        val id = lists.createList("Trip", ListIcon.TRAVEL, ListColor.SKY)
        val vm = CreateListViewModel(id, lists)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Trip", vm.uiState.value.name)
        assertFalse(vm.uiState.value.isDirty)
        vm.onNameChange("Trip to Japan")
        assertTrue(vm.uiState.value.isDirty)
    }
}

/**
 * FB-301-NB2 pickup: the `photoRef`/`setPhotoRef` path had zero direct `commonTest`
 * coverage. These exercise [ItemDetailViewModel]'s happy-path wiring to the FB-302-redesigned
 * [com.fluxit.data.PhotoStorage] contract (`replacePhoto`'s ordering itself is proven
 * exhaustively, including every failure branch, by `PhotoReplaceContractTest` - these tests
 * only need to prove the ViewModel is wired to it correctly).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var lists: FakeListRepository
    private lateinit var items: FakeItemRepository
    private lateinit var photoStorage: FakePhotoStorage
    private lateinit var photoPicker: FakePhotoPicker
    private lateinit var listId: String
    private lateinit var itemId: String

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        lists = FakeListRepository()
        items = FakeItemRepository()
        photoStorage = FakePhotoStorage()
        photoPicker = FakePhotoPicker()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * `FB-303`: `photoPreparer` defaults to the real, platform-decoding [preparePhotoForUpload]
     * in production; tests use an identity passthrough so existing fake picked-bytes (e.g.
     * `byteArrayOf(2)`) keep working without exercising a real image decoder (Android's
     * `testDebugUnitTest` has no Robolectric and would fail on a real `BitmapFactory` call -
     * see the KDoc on `ImageTransform.android.kt`). [pickPhotoRunsBytesThroughThePhotoPreparerBeforeUploading]
     * below is the dedicated test for the preparer actually being consulted.
     */
    private fun viewModel(photoPreparer: (ByteArray) -> ByteArray = { it }) =
        ItemDetailViewModel(listId, itemId, items, lists, photoPicker, photoStorage, photoPreparer)

    /** Creates a list+item and gives the item an already-uploaded photo, mirroring what a
     * real prior session would have persisted. */
    private suspend fun seedItemWithPhoto(): String {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        itemId = items.observeItems(listId).first().first().id
        val ref = photoStorage.uploadPhoto(itemId, byteArrayOf(7))
        items.setPhotoRef(listId, itemId, ref)
        return ref
    }

    @Test
    fun initExposesExistingPhotoRefAndResolvesAPreview() = runTest(dispatcher) {
        val ref = seedItemWithPhoto()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ref, vm.uiState.value.photoRef)
        val preview = assertIs<PhotoContent.Bytes>(vm.uiState.value.photoPreview)
        assertEquals(listOf<Byte>(7), preview.bytes.toList())
    }

    @Test
    fun itemWithNoPhotoHasNoPreview() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        itemId = items.observeItems(listId).first().first().id
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.photoRef)
        assertNull(vm.uiState.value.photoPreview)
    }

    @Test
    fun pickPhotoReplacesTheOldPhotoAndResetsThePickingFlag() = runTest(dispatcher) {
        val oldRef = seedItemWithPhoto()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        photoPicker.nextPick = byteArrayOf(2)
        vm.pickPhoto()
        dispatcher.scheduler.advanceUntilIdle()

        val newRef = vm.uiState.value.photoRef
        assertNotNull(newRef)
        assertTrue(newRef != oldRef)
        assertEquals(newRef, items.observeItem(listId, itemId).first()?.photoRef)
        assertNull(photoStorage.objects[oldRef], "old object must be deleted once the replace commits")
        assertFalse(vm.uiState.value.isPickingPhoto)
    }

    @Test
    fun pickPhotoRunsBytesThroughThePhotoPreparerBeforeUploading() = runTest(dispatcher) {
        // FB-303: proves pickPhoto() actually composes photoPreparer ahead of uploadPhoto -
        // the storage object and the preview must reflect the *prepared* bytes, not the raw
        // ones the picker returned.
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        itemId = items.observeItems(listId).first().first().id
        val vm = viewModel(photoPreparer = { raw -> raw.map { byte -> (byte + 1).toByte() }.toByteArray() })
        dispatcher.scheduler.advanceUntilIdle()

        photoPicker.nextPick = byteArrayOf(2)
        vm.pickPhoto()
        dispatcher.scheduler.advanceUntilIdle()

        val ref = vm.uiState.value.photoRef
        assertNotNull(ref)
        assertEquals(listOf<Byte>(3), photoStorage.objects[ref]?.toList(), "the prepared bytes, not the raw picked bytes, must be uploaded")
        val preview = assertIs<PhotoContent.Bytes>(vm.uiState.value.photoPreview)
        assertEquals(listOf<Byte>(3), preview.bytes.toList())
    }

    @Test
    fun removePhotoClearsReferenceAndDeletesTheObject() = runTest(dispatcher) {
        val ref = seedItemWithPhoto()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.removePhoto()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.photoRef)
        assertNull(vm.uiState.value.photoPreview)
        assertNull(items.observeItem(listId, itemId).first()?.photoRef)
        assertNull(photoStorage.objects[ref])
    }

    @Test
    fun deleteItemRemovesTheItemAndBestEffortDeletesItsPhoto() = runTest(dispatcher) {
        val ref = seedItemWithPhoto()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.deleteItem()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.closed)
        assertNull(items.observeItem(listId, itemId).first())
        assertNull(photoStorage.objects[ref])
    }

    // --- FB-306: progress/retry/failure states, layered on FB-302's proven replacePhoto() ---

    /** `FB-302-NB3`: an upload failure must leave the old photo fully intact and surface a
     * retryable failure, not propagate uncaught. */
    @Test
    fun pickPhotoUploadFailurePreservesTheOldPhotoAndSetsARetryableReplaceError() = runTest(dispatcher) {
        val oldRef = seedItemWithPhoto()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        photoStorage.failUpload = IllegalStateException("upload boom")
        photoPicker.nextPick = byteArrayOf(2)
        vm.pickPhoto()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(oldRef, vm.uiState.value.photoRef, "old photoRef must survive an upload failure")
        val preview = assertIs<PhotoContent.Bytes>(vm.uiState.value.photoPreview)
        assertEquals(listOf<Byte>(7), preview.bytes.toList(), "old preview must survive an upload failure")
        assertEquals(PhotoOperationKind.REPLACE, vm.uiState.value.photoOperationFailed)
        assertFalse(vm.uiState.value.isPickingPhoto)
        assertEquals(oldRef, items.observeItem(listId, itemId).first()?.photoRef, "Firestore-side ref must be untouched too")
        assertEquals(1, photoStorage.objects.size, "a failed upload must never leave a new object behind")
    }

    /** `FB-302-NB3`: a document-write failure (upload succeeds, `setPhotoRef` fails) must also
     * leave the old photo referenced and intact - the newly uploaded object becomes the
     * documented, accepted, sweep-reclaimable orphan (`PhotoStorage`'s KDoc), not a lost
     * reference or a silently swallowed crash. */
    @Test
    fun pickPhotoDocumentWriteFailurePreservesTheOldPhotoAndSetsARetryableReplaceError() = runTest(dispatcher) {
        val oldRef = seedItemWithPhoto()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        items.failSetPhotoRef = IllegalStateException("firestore write failed")
        photoPicker.nextPick = byteArrayOf(2)
        vm.pickPhoto()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(oldRef, vm.uiState.value.photoRef, "old photoRef must survive a document-write failure")
        val preview = assertIs<PhotoContent.Bytes>(vm.uiState.value.photoPreview)
        assertEquals(listOf<Byte>(7), preview.bytes.toList())
        assertEquals(PhotoOperationKind.REPLACE, vm.uiState.value.photoOperationFailed)
        assertFalse(vm.uiState.value.isPickingPhoto)
        assertEquals(oldRef, items.observeItem(listId, itemId).first()?.photoRef)
        assertEquals(
            2, photoStorage.objects.size,
            "the upload itself succeeded, so exactly one real-but-unreferenced orphan exists alongside the still-referenced old photo",
        )
        assertEquals(listOf<Byte>(7).size, photoStorage.objects[oldRef]?.size, "old object bytes untouched")
    }

    /** `FB-303-NB2`: a `photoPreparer` rejection (e.g. unsupported type/too large) must behave
     * exactly like an upload failure from the UI's point of view - old photo preserved,
     * retryable error surfaced, no object ever created since the rejection happens before
     * `uploadPhoto` is ever called. */
    @Test
    fun photoPreparerRejectionPreservesTheOldPhotoAndSetsARetryableReplaceError() = runTest(dispatcher) {
        val oldRef = seedItemWithPhoto()
        var shouldReject = true
        val vm = viewModel(photoPreparer = { bytes -> if (shouldReject) throw PhotoRejected.Corrupt else bytes })
        dispatcher.scheduler.advanceUntilIdle()

        photoPicker.nextPick = byteArrayOf(2)
        vm.pickPhoto()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(oldRef, vm.uiState.value.photoRef)
        assertEquals(PhotoOperationKind.REPLACE, vm.uiState.value.photoOperationFailed)
        assertFalse(vm.uiState.value.isPickingPhoto)
        assertEquals(1, photoStorage.objects.size, "a rejected photo must never reach uploadPhoto")

        // The condition clears (e.g. a smaller/supported replacement was effectively chosen) and
        // the user taps retry - retry must redrive the pipeline from the cached raw picked bytes,
        // without reopening the system picker (photoPicker.nextPick is already consumed/null).
        shouldReject = false
        vm.retryPhotoOperation()
        dispatcher.scheduler.advanceUntilIdle()

        val newRef = vm.uiState.value.photoRef
        assertNotNull(newRef)
        assertTrue(newRef != oldRef)
        assertNull(vm.uiState.value.photoOperationFailed)
        assertEquals(newRef, items.observeItem(listId, itemId).first()?.photoRef)
        assertNull(photoStorage.objects[oldRef], "old object must be deleted once the retried replace commits")
    }

    /** `FB-306` acceptance criterion (a): retrying after an upload failure must succeed using
     * the same cached bytes, without ever re-invoking the system photo picker. */
    @Test
    fun retryAfterAnUploadFailureSucceedsWithoutReopeningThePicker() = runTest(dispatcher) {
        val oldRef = seedItemWithPhoto()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        photoStorage.failUpload = IllegalStateException("upload boom")
        photoPicker.nextPick = byteArrayOf(2)
        vm.pickPhoto()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(PhotoOperationKind.REPLACE, vm.uiState.value.photoOperationFailed)

        photoStorage.failUpload = null
        vm.retryPhotoOperation()
        dispatcher.scheduler.advanceUntilIdle()

        val newRef = vm.uiState.value.photoRef
        assertNotNull(newRef)
        assertTrue(newRef != oldRef)
        assertNull(vm.uiState.value.photoOperationFailed)
        assertEquals(newRef, items.observeItem(listId, itemId).first()?.photoRef)
        assertNull(photoStorage.objects[oldRef], "old object must be deleted once the retried replace commits")
        assertEquals(1, photoStorage.objects.size, "exactly one object - the new one - must be referenced/left behind")
        assertNull(photoPicker.nextPick, "retry must not consume a second pick - it never calls the picker again")
    }

    /**
     * `FB-306` acceptance criterion (b), the sharper case: retrying after a *document-write*
     * failure (which - unlike an upload failure - already left one real, unreferenced,
     * sweep-reclaimable orphan behind per `PhotoStorage`'s documented contract) must still end
     * with the item referencing *exactly one* object, and the photo that was current *before
     * the whole replace attempt started* correctly deleted - never left stale, and never
     * "duplicated" as if the item pointed at two live photos at once. The disclosed,
     * accepted-by-contract orphan from the failed first attempt is asserted explicitly here
     * (not hidden) rather than silently ignored - retry re-drives the full upload step too
     * (it does not special-case "resume from an already-uploaded object"), so retrying a
     * document-write failure can leave one such orphan; what must never happen is the item
     * ending up with a stale or duplicate *reference*, which this test proves.
     */
    @Test
    fun retryAfterADocumentWriteFailureEndsWithExactlyOneReferencedPhotoAndTheEarlierOrphanIsAccountedFor() = runTest(dispatcher) {
        val oldRef = seedItemWithPhoto()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        items.failSetPhotoRef = IllegalStateException("firestore write failed")
        photoPicker.nextPick = byteArrayOf(2)
        vm.pickPhoto()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(PhotoOperationKind.REPLACE, vm.uiState.value.photoOperationFailed)
        assertEquals(oldRef, vm.uiState.value.photoRef)
        assertEquals(2, photoStorage.objects.size, "sanity: one disclosed orphan exists before the retry")

        items.failSetPhotoRef = null
        vm.retryPhotoOperation()
        dispatcher.scheduler.advanceUntilIdle()

        val finalRef = vm.uiState.value.photoRef
        assertNotNull(finalRef)
        assertNull(vm.uiState.value.photoOperationFailed)
        assertEquals(finalRef, items.observeItem(listId, itemId).first()?.photoRef, "exactly one referenced photo - no stale/duplicate reference")
        assertNull(photoStorage.objects[oldRef], "the photo that was current before the whole attempt started must be deleted, not left stale")
        assertEquals(
            2, photoStorage.objects.size,
            "the retry's own upload plus the first attempt's disclosed orphan - both real objects, only one of them referenced",
        )
        assertIs<PhotoContent.Bytes>(photoStorage.loadPhoto(finalRef))
    }

    /** `FB-302-NB3`'s sibling gap on the remove path: a `setPhotoRef(null)` failure must leave
     * the photo fully referenced/intact (the best-effort delete never even runs) and surface a
     * retryable failure instead of crashing the coroutine. */
    @Test
    fun removePhotoDocumentWriteFailurePreservesThePhotoAndSetsARetryableRemoveError() = runTest(dispatcher) {
        val ref = seedItemWithPhoto()
        items.failSetPhotoRef = IllegalStateException("firestore write failed")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.removePhoto()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ref, vm.uiState.value.photoRef)
        assertIs<PhotoContent.Bytes>(vm.uiState.value.photoPreview)
        assertEquals(PhotoOperationKind.REMOVE, vm.uiState.value.photoOperationFailed)
        assertFalse(vm.uiState.value.isRemovingPhoto)
        assertEquals(ref, items.observeItem(listId, itemId).first()?.photoRef)
        assertEquals(listOf<Byte>(7).size, photoStorage.objects[ref]?.size, "delete must never even be attempted before the reference clear succeeds")
    }

    @Test
    fun retryPhotoOperationRetriesAFailedRemoveAndSucceeds() = runTest(dispatcher) {
        val ref = seedItemWithPhoto()
        items.failSetPhotoRef = IllegalStateException("firestore write failed")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.removePhoto()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(PhotoOperationKind.REMOVE, vm.uiState.value.photoOperationFailed)

        items.failSetPhotoRef = null
        vm.retryPhotoOperation()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.photoRef)
        assertNull(vm.uiState.value.photoPreview)
        assertNull(vm.uiState.value.photoOperationFailed)
        assertNull(items.observeItem(listId, itemId).first()?.photoRef)
        assertNull(photoStorage.objects[ref])
    }

    @Test
    fun dismissPhotoErrorClearsTheFailedStateWithoutRetrying() = runTest(dispatcher) {
        val oldRef = seedItemWithPhoto()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        photoStorage.failUpload = IllegalStateException("upload boom")
        photoPicker.nextPick = byteArrayOf(2)
        vm.pickPhoto()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(PhotoOperationKind.REPLACE, vm.uiState.value.photoOperationFailed)

        vm.dismissPhotoError()

        assertNull(vm.uiState.value.photoOperationFailed)
        assertEquals(oldRef, vm.uiState.value.photoRef, "dismissing must not itself change the photo")
        assertEquals(1, photoStorage.objects.size, "dismissing must not attempt another upload")
    }

    /** `FB-302-NB1`-adjacent at the UI layer: a photo operation already in flight must block a
     * second, different photo operation rather than letting them race. */
    @Test
    fun removePhotoIsIgnoredWhileAReplaceIsAlreadyInFlight() = runTest(dispatcher) {
        seedItemWithPhoto()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        photoPicker.nextPick = byteArrayOf(2)
        vm.pickPhoto() // synchronously marks isPickingPhoto = true before any suspension point
        vm.removePhoto() // must be a no-op: a replace is already in flight
        dispatcher.scheduler.advanceUntilIdle()

        val newRef = vm.uiState.value.photoRef
        assertNotNull(newRef, "removePhoto must not have cleared the ref while the replace was in flight")
        assertEquals(newRef, items.observeItem(listId, itemId).first()?.photoRef)
    }
}
