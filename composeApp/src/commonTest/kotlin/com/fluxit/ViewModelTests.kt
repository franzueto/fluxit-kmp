package com.fluxit

import com.fluxit.data.DebugSeeder
import com.fluxit.data.PhotoContent
import com.fluxit.data.PhotoRejected
import com.fluxit.data.PhotoStorageException
import com.fluxit.data.remote.ApplicationError
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.RepositoryException
import com.fluxit.data.remote.toApplicationError
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.domain.ScreenLoadState
import com.fluxit.feature.createlist.CreateListViewModel
import com.fluxit.feature.dashboard.DashboardOperation
import com.fluxit.feature.dashboard.DashboardViewModel
import com.fluxit.feature.itemdetail.ItemDetailViewModel
import com.fluxit.feature.itemdetail.PhotoOperationKind
import com.fluxit.feature.listdetail.ListDetailOperation
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** Mapped failures the repository adapters can raise: a denied write, offline and a timeout. */
private val MAPPED_ERROR_CODES = listOf(RepositoryErrorCode.FORBIDDEN, RepositoryErrorCode.OFFLINE, RepositoryErrorCode.TIMEOUT)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var lists: FakeListRepository
    private lateinit var items: FakeItemRepository
    private lateinit var auth: FakeAuthRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        lists = FakeListRepository()
        items = FakeItemRepository()
        // FB-404: authenticated up front - this ViewModel is only ever constructed once
        // SessionGate has already reached Ready in production, so every pre-existing test
        // (which exercises the already-Authenticated steady state) needs this, and the new
        // fatal-session tests below start from here and move away from it explicitly.
        auth = FakeAuthRepository()
        runBlocking { auth.signUp("dashboard-test@example.com", "password123") }
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = DashboardViewModel(lists, DebugSeeder(lists, items), auth)

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

    @Test
    fun deleteListFailureEmitsADeleteFailureForThatListAndSuccessDoesNot() = runTest(dispatcher) {
        val id = lists.createList("Supermarket", ListIcon.CART, ListColor.ORANGE)
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        val failures = mutableListOf<String>()
        val failureJob = launch { vm.deleteFailures.collect { failures += it } }
        dispatcher.scheduler.advanceUntilIdle()

        lists.failSoftDeleteList = IllegalStateException("boom")
        vm.deleteList(id)
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(id), failures, "a failed delete must signal its row to return to rest")

        vm.deleteList(id)
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(id, id), failures, "a repeated failure must signal again")

        lists.failSoftDeleteList = null
        vm.deleteList(id)
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(id, id), failures, "a successful delete must not signal")
        failureJob.cancel()
        collectJob.cancel()
    }

    @Test
    fun deleteListFailureSurfacesTheRepositoryExceptionsMappedErrorAndRetryability() = runTest(dispatcher) {
        for (code in MAPPED_ERROR_CODES) {
            val failingLists = FakeListRepository()
            val id = failingLists.createList("Supermarket", ListIcon.CART, ListColor.ORANGE)
            val vm = DashboardViewModel(failingLists, DebugSeeder(failingLists, items), auth)
            val collectJob = launch { vm.uiState.collect {} }
            dispatcher.scheduler.advanceUntilIdle()

            failingLists.failSoftDeleteList = RepositoryException(code.toApplicationError())
            vm.deleteList(id)
            dispatcher.scheduler.runCurrent()

            val error = assertNotNull(vm.uiState.value.operationError, "$code")
            assertEquals(code, error.error.code)
            assertEquals(code != RepositoryErrorCode.FORBIDDEN, error.error.canRetry, "$code")
            collectJob.cancel()
        }
    }

    // --- FB-402: try/finally flag resets, retryable errors, duplicate-submit guards ---

    @Test
    fun deleteListFailureResetsPendingFlagAndSurfacesARetryableErrorThenRetrySucceeds() = runTest(dispatcher) {
        val id = lists.createList("Supermarket", ListIcon.CART, ListColor.ORANGE)
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        lists.failSoftDeleteList = IllegalStateException("boom")
        vm.deleteList(id)
        // `runCurrent()`, not `advanceUntilIdle()`: the failure path never suspends on `delay`,
        // and using `advanceUntilIdle()` here would also fast-forward past the 5s undo window
        // started by any *later* successful delete before this test gets to observe it.
        dispatcher.scheduler.runCurrent()

        assertTrue(vm.uiState.value.lists.isNotEmpty(), "a failed delete must not remove the list from the UI")
        assertTrue(vm.uiState.value.pendingListIds.isEmpty(), "the in-flight flag must reset on failure (finally)")
        assertNull(vm.undoListId.value, "a failed delete must never show an undo affordance")
        val error = assertNotNull(vm.uiState.value.operationError)
        assertEquals(DashboardOperation.DELETE_LIST, error.operation)
        assertTrue(error.error.canRetry)
        assertEquals(RepositoryErrorCode.UNKNOWN, error.error.code)

        lists.failSoftDeleteList = null
        vm.retryFailedOperation()
        dispatcher.scheduler.runCurrent()

        assertTrue(vm.uiState.value.lists.isEmpty(), "the retried delete must succeed")
        assertNull(vm.uiState.value.operationError)
        assertNotNull(vm.undoListId.value, "the retried delete must start the undo window like any other successful delete")
        collectJob.cancel()
    }

    @Test
    fun duplicateDeleteCallsWhileInFlightDoNotTriggerASecondSoftDelete() = runTest(dispatcher) {
        val id = lists.createList("Supermarket", ListIcon.CART, ListColor.ORANGE)
        val vm = viewModel()

        vm.deleteList(id) // synchronously marks the list id pending before any suspension point
        vm.deleteList(id) // must be a no-op: a delete for this same id is already in flight
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, lists.softDeleteListCallCount)
    }

    @Test
    fun undoDeleteFailureSurfacesARetryableRestoreErrorThenRetrySucceeds() = runTest(dispatcher) {
        val id = lists.createList("Supermarket", ListIcon.CART, ListColor.ORANGE)
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        vm.deleteList(id)
        // `runCurrent()`: keep the 5s undo window alive so `undoDelete()` below actually has a
        // pending id to act on, instead of `advanceUntilIdle()` fast-forwarding past it first.
        dispatcher.scheduler.runCurrent()

        lists.failRestoreList = IllegalStateException("boom")
        vm.undoDelete()
        dispatcher.scheduler.runCurrent()

        assertTrue(vm.uiState.value.lists.isEmpty(), "a failed restore must leave the list deleted")
        assertTrue(vm.uiState.value.pendingListIds.isEmpty(), "the in-flight flag must reset on failure (finally)")
        val error = assertNotNull(vm.uiState.value.operationError)
        assertEquals(DashboardOperation.RESTORE_LIST, error.operation)
        assertTrue(error.error.canRetry)

        lists.failRestoreList = null
        vm.retryFailedOperation()
        dispatcher.scheduler.runCurrent()

        assertEquals(1, vm.uiState.value.lists.size, "the retried restore must succeed")
        assertNull(vm.uiState.value.operationError)
        collectJob.cancel()
    }

    /** The same duplicate-submit guard must also protect a retry: two rapid-fire
     * [DashboardViewModel.retryFailedOperation] calls for the same failed delete must not
     * re-drive [FakeListRepository.softDeleteList] twice. */
    @Test
    fun duplicateRetryCallsWhileInFlightDoNotTriggerASecondSoftDelete() = runTest(dispatcher) {
        val id = lists.createList("Supermarket", ListIcon.CART, ListColor.ORANGE)
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        lists.failSoftDeleteList = IllegalStateException("boom")
        vm.deleteList(id)
        dispatcher.scheduler.runCurrent()
        assertNotNull(vm.uiState.value.operationError)
        assertEquals(1, lists.softDeleteListCallCount)

        lists.failSoftDeleteList = null
        vm.retryFailedOperation() // synchronously marks the list id pending before any suspension point
        vm.retryFailedOperation() // must be a no-op: a retry for this same id is already in flight
        dispatcher.scheduler.runCurrent()

        assertEquals(2, lists.softDeleteListCallCount, "exactly one retry attempt must have gone through")
        collectJob.cancel()
    }

    // --- FB-404: loading/empty/loaded/cached/pending-writes/fatal-session state machine ---

    @Test
    fun initialStateIsLoadingBeforeAnyEmission() {
        // Deliberately no `launch { collect }`/`advanceUntilIdle()`: `uiState` is a `stateIn`
        // hot flow seeded with `DashboardUiState()` (`loadState = ScreenLoadState.Loading`), so
        // reading `.value` synchronously right after construction - before the upstream
        // `combine` has ever run - proves the seed value itself is the "never loaded yet" state,
        // not merely that the ViewModel reaches it eventually.
        val vm = viewModel()

        assertTrue(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.isFatalSession)
        assertTrue(vm.uiState.value.lists.isEmpty())
    }

    @Test
    fun emptyEmissionIsLoadedAndEmptyNotLoading() = runTest(dispatcher) {
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.isFatalSession)
        assertTrue(state.isEmpty)
        assertTrue(state.lists.isEmpty())
        assertIs<ScreenLoadState.Loaded<*>>(state.loadState)
        collectJob.cancel()
    }

    @Test
    fun nonEmptyEmissionIsLoadedAndNotEmpty() = runTest(dispatcher) {
        lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.isEmpty)
        assertEquals(1, state.lists.size)
        assertFalse(state.isFromCache)
        assertFalse(state.hasPendingWrites)
        collectJob.cancel()
    }

    @Test
    fun cachedEmissionSetsIsFromCache() = runTest(dispatcher) {
        lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        lists.isFromCache = true
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.isFromCache)
        assertFalse(state.hasPendingWrites)
        assertFalse(state.isLoading, "cached data is still loaded data, not a loading state")
        collectJob.cancel()
    }

    @Test
    fun pendingWritesEmissionSetsHasPendingWrites() = runTest(dispatcher) {
        lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        lists.hasPendingWrites = true
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.hasPendingWrites)
        assertFalse(state.isFromCache)
        collectJob.cancel()
    }

    @Test
    fun sessionNoLongerAuthenticatedShowsFatalSession() = runTest(dispatcher) {
        lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.isFatalSession, "must start Loaded, not fatal, while authenticated")

        auth.signOut()
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.isFatalSession)
        assertIs<ScreenLoadState.FatalSession>(state.loadState)
        collectJob.cancel()
    }

    // --- FB-408: a terminal listener error must never crash - it must map to FatalSession ---

    /**
     * `FB-408`: reproduces `FB-405`'s headline finding at the ViewModel layer - before this
     * task's fix, `listLoadState`'s `combine(...)` had no `.catch`, so
     * [FakeListRepository.observeListSummariesSnapshot]'s `callbackFlow` closing with an
     * exception (exactly `AndroidFirebaseListRepository`/`IosFirebaseListRepository`'s real
     * `close(exception)` on a terminal Firestore listener error, e.g. `PERMISSION_DENIED` once
     * the backing auth token is invalidated) would rethrow uncaught through `viewModelScope`'s
     * `Dispatchers.Main.immediate` and fail this coroutine test with that exact exception
     * instead of ever reaching `uiState` - a real (JVM-level) reproduction of the crash
     * mechanism, not merely an assertion that it should be caught. With the fix, the same
     * trigger now surfaces as a recoverable [ScreenLoadState.FatalSession] and the collector
     * coroutine survives.
     */
    @Test
    fun terminalListenerErrorSurfacesFatalSessionInsteadOfCrashing() = runTest(dispatcher) {
        lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.isFatalSession, "must start Loaded, not fatal, before the listener errors")

        lists.failListenerWith(IllegalStateException("PERMISSION_DENIED (fake terminal listener error)"))
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.isFatalSession, "a terminal listener error must surface as FatalSession, not crash")
        assertIs<ScreenLoadState.FatalSession>(state.loadState)
        assertTrue(collectJob.isActive, "the uiState collector coroutine must survive the listener error")
        collectJob.cancel()
    }

    /** `FB-408`: a terminal listener error observed *before* the very first emission (the
     * stale-persisted-session-at-cold-launch trigger `FB-405` documented on iOS) must resolve
     * straight to [ScreenLoadState.FatalSession], never leave `isLoading` stuck `true` forever. */
    @Test
    fun terminalListenerErrorBeforeFirstEmissionResolvesToFatalSessionNotStuckLoading() = runTest(dispatcher) {
        lists.failListenerWith(IllegalStateException("PERMISSION_DENIED (fake terminal listener error)"))
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading, "must resolve, not stay stuck Loading forever")
        assertTrue(state.isFatalSession)
        collectJob.cancel()
    }

    // FB-504: cleanup now belongs to the scheduled backend, not dashboard startup.
    @Test
    fun dashboardConstructionDoesNotPurgeExpiredData() = runTest(dispatcher) {
        lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)

        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, lists.purgeExpiredCallCount, "dashboard startup must leave cleanup to the backend")
        assertEquals(1, vm.uiState.value.lists.size, "normal list observation must still start")
        collectJob.cancel()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ListDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var lists: FakeListRepository
    private lateinit var items: FakeItemRepository
    private lateinit var auth: FakeAuthRepository
    private lateinit var listId: String

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        lists = FakeListRepository()
        items = FakeItemRepository()
        // FB-404: authenticated up front - see `DashboardViewModelTest.setUp`'s identical
        // rationale.
        auth = FakeAuthRepository()
        runBlocking { auth.signUp("listdetail-test@example.com", "password123") }
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ListDetailViewModel(listId, lists, items, auth)

    @Test
    fun composerSubmitAddsItemAndClearsText() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        val vm = viewModel()
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
        val vm = viewModel()

        vm.onComposerChange("   ")
        vm.submitComposer()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(items.rows.value.isEmpty())
    }

    @Test
    fun toggleMovesItemBetweenSections() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        val vm = viewModel()
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
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        vm.clearCompleted()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("Bread"), vm.uiState.value.activeItems.map { it.title })
        assertTrue(vm.uiState.value.completedItems.isEmpty())
        collectJob.cancel()
    }

    @Test
    fun deleteItemFailureEmitsADeleteFailureForThatItemAndSuccessDoesNot() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        val itemId = items.observeItems(listId).first().first().id
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        val failures = mutableListOf<String>()
        val failureJob = launch { vm.itemDeleteFailures.collect { failures += it } }
        dispatcher.scheduler.advanceUntilIdle()

        items.failSoftDeleteItem = IllegalStateException("boom")
        vm.deleteItem(itemId)
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(itemId), failures, "a failed delete must signal its row to return to rest")

        items.failSoftDeleteItem = null
        vm.deleteItem(itemId)
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(itemId), failures, "a successful delete must not signal")
        failureJob.cancel()
        collectJob.cancel()
    }

    @Test
    fun addItemFailureSurfacesTheRepositoryExceptionsMappedErrorAndRetryability() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        for (code in MAPPED_ERROR_CODES) {
            val vm = viewModel()
            val collectJob = launch { vm.uiState.collect {} }
            vm.onComposerChange("Milk")

            items.failAddItem = RepositoryException(code.toApplicationError())
            vm.submitComposer()
            dispatcher.scheduler.advanceUntilIdle()

            val error = assertNotNull(vm.operationError.value, "$code")
            assertEquals(code, error.error.code)
            assertEquals(code != RepositoryErrorCode.FORBIDDEN, error.error.canRetry, "$code")
            collectJob.cancel()
        }
    }

    // --- FB-403: try/finally flag reset, retryable error, duplicate-submit guard ---

    @Test
    fun addItemFailureResetsIsAddingItemAndSurfacesARetryableErrorThenRetrySucceeds() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        vm.onComposerChange("Milk")

        items.failAddItem = IllegalStateException("boom")
        vm.submitComposer()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.isAddingItem.value, "isAddingItem must reset on failure (finally), not stay stuck true")
        assertTrue(vm.uiState.value.activeItems.isEmpty())
        val error = assertNotNull(vm.operationError.value)
        assertEquals(ListDetailOperation.ADD_ITEM, error.operation)
        assertTrue(error.error.canRetry)
        assertEquals(RepositoryErrorCode.UNKNOWN, error.error.code)

        items.failAddItem = null
        vm.retryFailedOperation()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.isAddingItem.value)
        assertNull(vm.operationError.value)
        assertEquals(listOf("Milk"), vm.uiState.value.activeItems.map { it.title }, "the retried add must succeed with the originally typed title")
        collectJob.cancel()
    }

    @Test
    fun duplicateSubmitComposerCallsWhileInFlightDoNotTriggerASecondAddItem() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        val vm = viewModel()
        vm.onComposerChange("Milk")

        vm.submitComposer() // synchronously marks isAddingItem = true before any suspension point
        vm.onComposerChange("Milk") // even if re-typed, a second submit while in flight is still a no-op
        vm.submitComposer()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, items.addItemCallCount)
    }

    @Test
    fun toggleCompletedFailureResetsPendingAndSurfacesARetryableErrorThenRetrySucceeds() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        val item = vm.uiState.value.activeItems.first()

        items.failSetCompleted = IllegalStateException("boom")
        vm.toggleCompleted(item)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.pendingItemIds.value.isEmpty(), "pendingItemIds must reset on failure (finally)")
        assertEquals(listOf("Milk"), vm.uiState.value.activeItems.map { it.title }, "a failed toggle must not move the item")
        val error = assertNotNull(vm.operationError.value)
        assertEquals(ListDetailOperation.TOGGLE_COMPLETED, error.operation)

        items.failSetCompleted = null
        vm.retryFailedOperation()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.operationError.value)
        assertTrue(vm.uiState.value.activeItems.isEmpty(), "the retried toggle must succeed")
        assertEquals(1, vm.uiState.value.completedItems.size)
        collectJob.cancel()
    }

    @Test
    fun duplicateToggleCompletedCallsForTheSameItemWhileInFlightDoNotTriggerASecondCall() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        val item = vm.uiState.value.activeItems.first()

        vm.toggleCompleted(item) // synchronously marks the item id pending before any suspension point
        vm.toggleCompleted(item) // must be a no-op: a toggle for this item is already in flight
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, items.setCompletedCallCount)
        collectJob.cancel()
    }

    @Test
    fun deleteItemFailureResetsPendingAndSurfacesARetryableErrorWithNoPhantomUndo() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        val itemId = vm.uiState.value.activeItems.first().id

        items.failSoftDeleteItem = IllegalStateException("boom")
        vm.deleteItem(itemId)
        // `runCurrent()`, not `advanceUntilIdle()`: the failure path never suspends on `delay`,
        // and `advanceUntilIdle()` here would also fast-forward past the 5s undo window started
        // by the *later* successful retry before this test gets to observe it - same reasoning
        // as `DashboardViewModelTest.deleteListFailureResetsPendingFlagAndSurfacesARetryableErrorThenRetrySucceeds` (`FB-402`).
        dispatcher.scheduler.runCurrent()

        assertTrue(vm.pendingItemIds.value.isEmpty(), "pendingItemIds must reset on failure (finally)")
        assertNull(vm.undoItemId.value, "a failed delete must never show an undo affordance")
        val error = assertNotNull(vm.operationError.value)
        assertEquals(ListDetailOperation.DELETE_ITEM, error.operation)

        items.failSoftDeleteItem = null
        vm.retryFailedOperation()
        dispatcher.scheduler.runCurrent()

        assertNull(vm.operationError.value)
        assertNotNull(vm.undoItemId.value, "the retried delete must start the undo window like any other successful delete")
        collectJob.cancel()
    }

    @Test
    fun duplicateDeleteItemCallsForTheSameItemWhileInFlightDoNotTriggerASecondCall() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        val itemId = vm.uiState.value.activeItems.first().id

        vm.deleteItem(itemId) // synchronously marks the item id pending before any suspension point
        vm.deleteItem(itemId) // must be a no-op: a delete for this item is already in flight
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, items.softDeleteItemCallCount)
        collectJob.cancel()
    }

    @Test
    fun undoDeleteFailureSurfacesARetryableRestoreErrorThenRetrySucceeds() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        val itemId = vm.uiState.value.activeItems.first().id
        vm.deleteItem(itemId)
        // `runCurrent()`, not `advanceUntilIdle()`: the latter would also fast-forward through
        // the 5s undo-expiry delay before `undoDelete()` below ever gets to cancel it, which
        // would clear `pendingUndo` out from under this test and make `undoDelete()` an
        // unintended no-op.
        dispatcher.scheduler.runCurrent()

        items.failRestoreItem = IllegalStateException("boom")
        vm.undoDelete()
        dispatcher.scheduler.runCurrent()

        assertTrue(vm.pendingItemIds.value.isEmpty())
        assertTrue(vm.uiState.value.activeItems.isEmpty(), "a failed restore must not bring the item back")
        val error = assertNotNull(vm.operationError.value)
        assertEquals(ListDetailOperation.RESTORE_ITEM, error.operation)

        items.failRestoreItem = null
        vm.retryFailedOperation()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.operationError.value)
        assertEquals(listOf("Milk"), vm.uiState.value.activeItems.map { it.title }, "the retried restore must succeed")
        collectJob.cancel()
    }

    @Test
    fun clearCompletedFailureResetsFlagAndSurfacesARetryableErrorThenRetrySucceeds() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        items.setCompleted(listId, items.observeItems(listId).first().first().id, true)
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        items.failClearCompleted = IllegalStateException("boom")
        vm.clearCompleted()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.isClearingCompleted.value, "isClearingCompleted must reset on failure (finally)")
        assertEquals(1, vm.uiState.value.completedItems.size, "a failed clear must not remove completed items")
        val error = assertNotNull(vm.operationError.value)
        assertEquals(ListDetailOperation.CLEAR_COMPLETED, error.operation)

        items.failClearCompleted = null
        vm.retryFailedOperation()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.operationError.value)
        assertTrue(vm.uiState.value.completedItems.isEmpty(), "the retried clear must succeed")
        collectJob.cancel()
    }

    @Test
    fun duplicateClearCompletedCallsWhileInFlightDoNotTriggerASecondCall() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        items.setCompleted(listId, items.observeItems(listId).first().first().id, true)
        val vm = viewModel()

        vm.clearCompleted() // synchronously marks isClearingCompleted = true before any suspension point
        vm.clearCompleted() // must be a no-op: a clear is already in flight
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, items.clearCompletedCallCount)
    }

    // --- FB-404: loading/empty/loaded/cached/pending-writes/fatal-session state machine ---

    @Test
    fun initialStateIsLoadingBeforeAnyEmission() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        // Deliberately no `launch { collect }`/`advanceUntilIdle()` - see
        // `DashboardViewModelTest.initialStateIsLoadingBeforeAnyEmission`'s identical rationale.
        val vm = viewModel()

        assertTrue(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.isFatalSession)
        assertTrue(vm.uiState.value.activeItems.isEmpty())
        assertTrue(vm.uiState.value.completedItems.isEmpty())
    }

    @Test
    fun emptyItemsEmissionIsLoadedAndEmptyNotLoading() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.isFatalSession)
        assertTrue(state.isEmpty)
        assertIs<ScreenLoadState.Loaded<*>>(state.itemsLoadState)
        collectJob.cancel()
    }

    @Test
    fun nonEmptyItemsEmissionIsLoadedAndNotEmpty() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.isEmpty)
        assertEquals(1, state.totalCount)
        assertFalse(state.isFromCache)
        assertFalse(state.hasPendingWrites)
        collectJob.cancel()
    }

    @Test
    fun cachedEmissionSetsIsFromCache() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        items.isFromCache = true
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.isFromCache)
        assertFalse(state.hasPendingWrites)
        assertFalse(state.isLoading, "cached data is still loaded data, not a loading state")
        collectJob.cancel()
    }

    @Test
    fun pendingWritesEmissionSetsHasPendingWrites() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        items.hasPendingWrites = true
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.hasPendingWrites)
        assertFalse(state.isFromCache)
        collectJob.cancel()
    }

    @Test
    fun sessionNoLongerAuthenticatedShowsFatalSession() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.isFatalSession, "must start Loaded, not fatal, while authenticated")

        auth.signOut()
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.isFatalSession)
        assertIs<ScreenLoadState.FatalSession>(state.itemsLoadState)
        collectJob.cancel()
    }

    // --- FB-408: a terminal listener error must never crash - it must map to FatalSession ---

    /** `FB-408`: exact counterpart of `DashboardViewModelTest`'s identically-named test - the
     * `FB-405` reviewer independently found this ViewModel had the same unguarded shape
     * (the original `FB-405` developer report only covered `DashboardViewModel`). See that
     * test's KDoc for the full rationale. */
    @Test
    fun terminalListenerErrorSurfacesFatalSessionInsteadOfCrashing() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.isFatalSession, "must start Loaded, not fatal, before the listener errors")

        items.failListenerWith(IllegalStateException("PERMISSION_DENIED (fake terminal listener error)"))
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.isFatalSession, "a terminal listener error must surface as FatalSession, not crash")
        assertIs<ScreenLoadState.FatalSession>(state.itemsLoadState)
        assertTrue(collectJob.isActive, "the uiState collector coroutine must survive the listener error")
        collectJob.cancel()
    }

    // --- FB-409: try/finally flag reset, retryable error, duplicate-submit guard for deleteList ---

    /**
     * `FB-409`/`FB-406-B1`: `deleteList()` was a bare `viewModelScope.launch { ... }` with no
     * `try/catch/finally` at all, zero test coverage, and no duplicate-submit guard - a real
     * Firestore failure would rethrow uncaught through `viewModelScope` and crash the app
     * process, the identical crash class `DEC-008`/`FB-408` fixed for this screen's
     * listener-observation chain ([ScreenLoadState.FatalSession] via `itemsLoadState`), just on
     * this mutation path instead. This proves the fix: the coroutine survives, [listDeleted]
     * stays `false` (the screen must not close on a delete that did not happen), a retryable
     * [ListDetailOperationError] is surfaced, and a successful retry closes the screen.
     */
    @Test
    fun deleteListFailureResetsFlagAndSurfacesARetryableErrorThenRetrySucceeds() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        lists.failSoftDeleteList = IllegalStateException("boom")
        vm.deleteList()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(collectJob.isActive, "a failed deleteList must never crash the ViewModel's coroutine scope")
        assertFalse(vm.isDeletingList.value, "isDeletingList must reset on failure (finally), not stay stuck true")
        assertFalse(vm.uiState.value.listDeleted, "a failed delete must not close the screen")
        val error = assertNotNull(vm.operationError.value)
        assertEquals(ListDetailOperation.DELETE_LIST, error.operation)
        assertTrue(error.error.canRetry)
        assertEquals(RepositoryErrorCode.UNKNOWN, error.error.code)

        lists.failSoftDeleteList = null
        vm.retryFailedOperation()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.isDeletingList.value)
        assertNull(vm.operationError.value)
        assertTrue(vm.uiState.value.listDeleted, "the retried delete must succeed and close the screen")
        collectJob.cancel()
    }

    @Test
    fun duplicateDeleteListCallsWhileInFlightDoNotTriggerASecondSoftDelete() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        val vm = viewModel()

        vm.deleteList() // synchronously marks isDeletingList = true before any suspension point
        vm.deleteList() // must be a no-op: a delete is already in flight
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, lists.softDeleteListCallCount)
    }

    // --- FB-409 sibling-audit fix: the list-document listener must never crash either ---

    /**
     * `FB-409`: this task's live cross-uid reproduction of `deleteList()`'s crash (run against
     * a real Firestore emulator) surfaced a second, distinct gap in the same `uiState`
     * `combine(...)`: `listRepository.observeList(listId)` was fed in directly with no `.catch`
     * at all - unlike [itemsLoadState]/`itemsLoadState`'s sibling flow, which `FB-408` did fix.
     * A terminal listener error on the *list document* itself therefore still rethrew uncaught
     * through `viewModelScope`'s `stateIn` and crashed the process, exactly `FB-405`'s original
     * finding, even though `FB-408` was marked `DONE` for this ViewModel. Proves the fix: the
     * coroutine survives and [ListDetailUiState.list] resolves to `null` instead.
     */
    @Test
    fun listDocumentListenerTerminalErrorResolvesToNullListInsteadOfCrashing() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        val vm = viewModel()
        val collectJob = launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.uiState.value.list, "must start with the real list loaded")

        lists.failListenerWith(IllegalStateException("PERMISSION_DENIED (fake terminal list-document listener error)"))
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(collectJob.isActive, "a terminal list-document listener error must never crash the ViewModel's coroutine scope")
        assertNull(vm.uiState.value.list, "must resolve to no list rather than propagate the error")
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

    @Test
    fun saveFailureSurfacesTheRepositoryExceptionsMappedErrorAndRetryability() = runTest(dispatcher) {
        for (code in MAPPED_ERROR_CODES) {
            val vm = CreateListViewModel(null, lists)
            vm.onNameChange("Trip")

            lists.failCreateList = RepositoryException(code.toApplicationError())
            vm.save()
            dispatcher.scheduler.advanceUntilIdle()

            val error = assertNotNull(vm.uiState.value.error, "$code")
            assertEquals(code, error.code)
            assertEquals(code != RepositoryErrorCode.FORBIDDEN, error.canRetry, "$code")
        }
    }

    // --- FB-402: try/finally flag reset, retryable error, duplicate-submit guard ---

    @Test
    fun saveFailureResetsIsSavingAndSurfacesARetryableErrorThenRetrySucceeds() = runTest(dispatcher) {
        val vm = CreateListViewModel(null, lists)
        vm.onNameChange("Trip")

        lists.failCreateList = IllegalStateException("boom")
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.isSaving, "isSaving must reset on failure (finally), not stay stuck true")
        assertNull(vm.uiState.value.savedListId)
        val error = assertNotNull(vm.uiState.value.error)
        assertTrue(error.canRetry)
        assertEquals(RepositoryErrorCode.UNKNOWN, error.code)

        lists.failCreateList = null
        vm.retrySave()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.isSaving)
        assertNull(vm.uiState.value.error)
        assertNotNull(vm.uiState.value.savedListId, "the retried save must succeed")
    }

    @Test
    fun saveFailureInEditModeResetsIsSavingAndSurfacesARetryableError() = runTest(dispatcher) {
        val id = lists.createList("Trip", ListIcon.TRAVEL, ListColor.SKY)
        val vm = CreateListViewModel(id, lists)
        dispatcher.scheduler.advanceUntilIdle()
        vm.onNameChange("Trip to Japan")

        lists.failUpdateList = IllegalStateException("boom")
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.isSaving)
        assertNull(vm.uiState.value.savedListId)
        assertNotNull(vm.uiState.value.error)
        assertEquals("Trip", lists.observeList(id).first()?.name, "a failed update must not change the stored list")

        lists.failUpdateList = null
        vm.retrySave()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.error)
        assertEquals("", vm.uiState.value.savedListId)
        assertEquals("Trip to Japan", lists.observeList(id).first()?.name)
    }

    @Test
    fun duplicateSaveCallsWhileInFlightDoNotTriggerASecondCreate() = runTest(dispatcher) {
        val vm = CreateListViewModel(null, lists)
        vm.onNameChange("Trip")

        vm.save() // synchronously marks isSaving = true before any suspension point
        vm.save() // must be a no-op: a save is already in flight
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, lists.createListCallCount)
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
    private lateinit var auth: FakeAuthRepository
    private lateinit var listId: String
    private lateinit var itemId: String

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        lists = FakeListRepository()
        items = FakeItemRepository()
        photoStorage = FakePhotoStorage()
        photoPicker = FakePhotoPicker()
        // FB-404: authenticated up front - see `DashboardViewModelTest.setUp`'s identical
        // rationale.
        auth = FakeAuthRepository()
        runBlocking { auth.signUp("itemdetail-test@example.com", "password123") }
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
        ItemDetailViewModel(listId, itemId, items, lists, photoPicker, photoStorage, auth, photoPreparer)

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

    // --- FB-403: try/finally flag reset, retryable error, duplicate-submit guard (save/delete) ---

    @Test
    fun saveFailureResetsIsSavingAndSurfacesARetryableErrorThenRetrySucceeds() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        itemId = items.observeItems(listId).first().first().id
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.onTitleChange("Whole milk")

        items.failUpdateItem = IllegalStateException("boom")
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.isSaving, "isSaving must reset on failure (finally), not stay stuck true")
        assertFalse(vm.uiState.value.closed)
        val error = assertNotNull(vm.uiState.value.saveError)
        assertTrue(error.canRetry)
        assertEquals(RepositoryErrorCode.UNKNOWN, error.code)
        assertEquals("Milk", items.observeItem(listId, itemId).first()?.title, "a failed save must not change the stored item")

        items.failUpdateItem = null
        vm.retrySave()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.isSaving)
        assertNull(vm.uiState.value.saveError)
        assertTrue(vm.uiState.value.closed, "the retried save must succeed")
        assertEquals("Whole milk", items.observeItem(listId, itemId).first()?.title)
    }

    @Test
    fun duplicateSaveCallsWhileInFlightDoNotTriggerASecondUpdateItem() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        itemId = items.observeItems(listId).first().first().id
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.onTitleChange("Whole milk")

        vm.save() // synchronously marks isSaving = true before any suspension point
        vm.save() // must be a no-op: a save is already in flight
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, items.updateItemCallCount)
    }

    @Test
    fun deleteItemFailureResetsIsDeletingItemAndSurfacesARetryableErrorThenRetrySucceeds() = runTest(dispatcher) {
        seedItemWithPhoto()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        items.failDeleteItem = IllegalStateException("boom")
        vm.deleteItem()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.isDeletingItem, "isDeletingItem must reset on failure (finally), not stay stuck true")
        assertFalse(vm.uiState.value.closed)
        val error = assertNotNull(vm.uiState.value.deleteError)
        assertTrue(error.canRetry)
        assertEquals(RepositoryErrorCode.UNKNOWN, error.code)
        assertNotNull(items.observeItem(listId, itemId).first(), "a failed delete must not remove the item")

        items.failDeleteItem = null
        vm.retryDeleteItem()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.isDeletingItem)
        assertNull(vm.uiState.value.deleteError)
        assertTrue(vm.uiState.value.closed, "the retried delete must succeed")
        assertNull(items.observeItem(listId, itemId).first())
    }

    @Test
    fun duplicateDeleteItemCallsWhileInFlightDoNotTriggerASecondDeleteItem() = runTest(dispatcher) {
        seedItemWithPhoto()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.deleteItem() // synchronously marks isDeletingItem = true before any suspension point
        vm.deleteItem() // must be a no-op: a delete is already in flight
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, items.deleteItemCallCount)
    }

    @Test
    fun dismissSaveErrorClearsTheFailedStateWithoutRetrying() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        itemId = items.observeItems(listId).first().first().id
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.onTitleChange("Whole milk")

        items.failUpdateItem = IllegalStateException("boom")
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.uiState.value.saveError)

        vm.dismissSaveError()

        assertNull(vm.uiState.value.saveError)
        assertFalse(vm.uiState.value.closed)
    }

    @Test
    fun saveFailureSurfacesTheRepositoryExceptionsMappedErrorAndRetryability() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        itemId = items.observeItems(listId).first().first().id
        for (code in MAPPED_ERROR_CODES) {
            val vm = viewModel()
            dispatcher.scheduler.advanceUntilIdle()
            vm.onTitleChange("Whole milk")

            items.failUpdateItem = RepositoryException(code.toApplicationError())
            vm.save()
            dispatcher.scheduler.advanceUntilIdle()

            val error = assertNotNull(vm.uiState.value.saveError, "$code")
            assertEquals(code, error.code)
            assertEquals(code != RepositoryErrorCode.FORBIDDEN, error.canRetry, "$code")
        }
    }

    /**
     * `FB-403` (discharging the remainder of `FB-305-NB2`/`FB-401-NB1`/`FB-401-NB2`): a
     * `PhotoStorage` failure must surface its real, specific [ApplicationError] - not just the
     * conservative `RepositoryErrorCode.UNKNOWN` fallback [toItemDetailApplicationError] uses
     * for non-`PhotoStorage` failures - proving `performReplace`'s catch block actually unwraps
     * [PhotoStorageException] rather than discarding it.
     */
    @Test
    fun pickPhotoFailureSurfacesThePhotoStorageExceptionsRealApplicationErrorNotJustUnknown() = runTest(dispatcher) {
        seedItemWithPhoto()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        val forbidden = ApplicationError(RepositoryErrorCode.FORBIDDEN, canRetry = false)
        photoStorage.failUpload = PhotoStorageException(forbidden)
        photoPicker.nextPick = byteArrayOf(2)
        vm.pickPhoto()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(PhotoOperationKind.REPLACE, vm.uiState.value.photoOperationFailed)
        val error = assertNotNull(vm.uiState.value.photoOperationError)
        assertEquals(RepositoryErrorCode.FORBIDDEN, error.code, "the real PhotoStorageException payload must be extracted, not collapsed to UNKNOWN")
        assertFalse(error.canRetry)
    }

    // --- FB-404: initial-loading vs. not-found vs. fatal-session ---

    @Test
    fun initialStateIsLoadingBeforeInitCompletes() = runTest(dispatcher) {
        seedItemWithPhoto()
        // Deliberately no `advanceUntilIdle()` - see `DashboardViewModelTest`'s identically
        // reasoned test: the `init` block's `viewModelScope.launch` has not run yet on this
        // `StandardTestDispatcher`, so `_uiState`'s default (`isLoading = true`) is still the
        // synchronously observable value.
        val vm = viewModel()

        assertTrue(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.isFatalSession)
        assertFalse(vm.uiState.value.notFound)
        assertNull(vm.uiState.value.item)
    }

    @Test
    fun existingItemResolvesToLoadedNotLoading() = runTest(dispatcher) {
        seedItemWithPhoto()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.isFatalSession)
        assertFalse(state.notFound)
        assertNotNull(state.item)
    }

    @Test
    fun missingItemResolvesToNotFoundNotLoading() = runTest(dispatcher) {
        listId = "list-that-does-not-exist"
        itemId = "item-that-does-not-exist"
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.isFatalSession)
        assertTrue(state.notFound)
        assertNull(state.item)
    }

    @Test
    fun sessionNotAuthenticatedAtLoadResolvesToFatalSessionNotLoading() = runTest(dispatcher) {
        seedItemWithPhoto()
        auth.signOut()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.isFatalSession)
        assertFalse(state.notFound)
        assertNull(state.item)
    }

    // --- FB-408: a terminal listener error during the initial load must never crash ---

    /**
     * `FB-408` re-audit finding: unlike `DashboardViewModel`/`ListDetailViewModel`, this
     * ViewModel has no `combine(...).stateIn(...)` chain - its `init` block does a one-shot
     * `itemRepository.observeItem(listId, itemId).first()` instead. But the same root cause
     * applies: before this task's fix, that `.first()` call had no `try`/`catch` around it, so
     * [FakeItemRepository.observeItem]'s `callbackFlow` closing with an exception (exactly
     * `AndroidFirebaseItemRepository`/`IosFirebaseItemRepository`'s real `close(exception)` on
     * a terminal Firestore listener error) would rethrow uncaught through `viewModelScope`'s
     * `Dispatchers.Main.immediate` and fail this coroutine test with that exact exception
     * instead of ever reaching `uiState` - confirming the `FB-405` reviewer's flagged-but-
     * unconfirmed concern was a real, present defect here too. With the fix, the same trigger
     * now surfaces as a recoverable `isFatalSession` state and the `init` coroutine survives.
     */
    @Test
    fun terminalListenerErrorDuringInitialLoadSurfacesFatalSessionInsteadOfCrashing() = runTest(dispatcher) {
        listId = lists.createList("Groceries", ListIcon.CART, ListColor.ORANGE)
        items.addItem(listId, "Milk")
        itemId = items.observeItems(listId).first().first().id
        items.failListenerWith(IllegalStateException("PERMISSION_DENIED (fake terminal listener error)"))

        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading, "must resolve, not stay stuck Loading forever")
        assertTrue(state.isFatalSession, "a terminal listener error must surface as FatalSession, not crash")
        assertFalse(state.notFound)
        assertNull(state.item)
    }
}
