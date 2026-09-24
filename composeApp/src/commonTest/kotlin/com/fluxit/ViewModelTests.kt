package com.fluxit

import com.fluxit.data.DebugSeeder
import com.fluxit.data.PhotoContent
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.feature.createlist.CreateListViewModel
import com.fluxit.feature.dashboard.DashboardViewModel
import com.fluxit.feature.itemdetail.ItemDetailViewModel
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

    private fun viewModel() = ItemDetailViewModel(listId, itemId, items, lists, photoPicker, photoStorage)

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
}
