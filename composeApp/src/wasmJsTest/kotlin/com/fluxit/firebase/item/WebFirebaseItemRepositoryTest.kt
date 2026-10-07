package com.fluxit.firebase.item

import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.data.remote.FirebaseValue
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.firebase.list.CurrentUidProvider
import com.fluxit.firebase.list.FixedUidProvider
import com.fluxit.data.remote.RepositoryException
import com.fluxit.firebase.list.firestoreError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for the web item adapter's own logic, ported from `IosFirebaseItemRepositoryTest`
 * (same matrix): listener lifecycle, mapping, field-scoped patches, the counter policy run
 * through `decide`, and clear-completed chunking. The JS bridge is replaced by
 * [RecordingItemBridge].
 *
 * Not proven here: that the Firebase JS SDK reports snapshots, errors and transactions as
 * the double assumes. The emulator run recorded in docs/web-app/PROGRESS.md covers that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebFirebaseItemRepositoryTest {

    private fun repositoryFor(
        bridge: WebFirestoreItemBridge,
        uid: CurrentUidProvider = FixedUidProvider(),
        clearCompletedChunkSize: Int = DEFAULT_CLEAR_COMPLETED_CHUNK_SIZE,
    ): WebFirebaseItemRepository = WebFirebaseItemRepository({ bridge }, uid, clearCompletedChunkSize)

    private fun <T> TestScope.collect(
        flow: kotlinx.coroutines.flow.Flow<T>,
        into: MutableList<T>,
    ): Job = (this as CoroutineScope).launch(UnconfinedTestDispatcher(testScheduler)) {
        flow.toList(into)
    }

    private fun activeDocument(
        id: String,
        listId: String = "list-1",
        title: String = "Milk",
        completed: Boolean = false,
        createdAt: Long = 1_000L,
        updatedAt: Long = 1_000L,
    ): WebFirestoreItemDocument = WebFirestoreItemDocument(
        id = id,
        fields = mapOf(
            FirebaseSchema.Fields.LIST_ID to FirebaseValue.Text(listId),
            FirebaseSchema.Fields.TITLE to FirebaseValue.Text(title),
            FirebaseSchema.Fields.DESCRIPTION to FirebaseValue.Null,
            FirebaseSchema.Fields.IS_COMPLETED to FirebaseValue.Bool(completed),
            FirebaseSchema.Fields.PHOTO_REF to FirebaseValue.Null,
            FirebaseSchema.Fields.CREATED_AT to FirebaseValue.Timestamp(createdAt),
            FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.Timestamp(updatedAt),
            FirebaseSchema.Fields.DELETED_AT to FirebaseValue.Null,
            FirebaseSchema.Fields.SCHEMA_VERSION to FirebaseValue.Number(FirebaseSchema.CURRENT_VERSION),
        ),
    )

    private fun tombstonedDocument(id: String, listId: String = "list-1", createdAt: Long = 1_000L): WebFirestoreItemDocument =
        WebFirestoreItemDocument(
            id = id,
            fields = activeDocument(id, listId = listId, createdAt = createdAt).fields +
                (FirebaseSchema.Fields.DELETED_AT to FirebaseValue.Timestamp(2_000L)),
        )

    private fun activeFields(completed: Boolean = false): Map<String, FirebaseValue> = mapOf(
        FirebaseSchema.Fields.IS_COMPLETED to FirebaseValue.Bool(completed),
        FirebaseSchema.Fields.DELETED_AT to FirebaseValue.Null,
    )

    private fun tombstonedFields(completed: Boolean = false): Map<String, FirebaseValue> = mapOf(
        FirebaseSchema.Fields.IS_COMPLETED to FirebaseValue.Bool(completed),
        FirebaseSchema.Fields.DELETED_AT to FirebaseValue.Timestamp(2_000L),
    )

    // --- listener lifecycle: observeItems -----------------------------------------------

    @Test
    fun collectingItemsRegistersExactlyOneListener() = runTest {
        val bridge = RecordingItemBridge()
        val repository = repositoryFor(bridge)

        val job = collect(repository.observeItems("list-1"), mutableListOf())

        assertEquals(1, bridge.itemsAddCount)
        assertTrue(bridge.hasLiveItemsListener)
        assertEquals(0, bridge.itemsRemoveCount)

        job.cancelAndJoin()
    }

    @Test
    fun cancellingTheItemsCollectorRemovesTheUnderlyingListener() = runTest {
        val bridge = RecordingItemBridge()
        val repository = repositoryFor(bridge)

        val job = collect(repository.observeItems("list-1"), mutableListOf())
        job.cancelAndJoin()

        assertEquals(1, bridge.itemsRemoveCount)
        assertFalse(bridge.hasLiveItemsListener, "the Firestore listener must be released")
    }

    @Test
    fun aReleasedItemsListenerStopsReachingTheCollector() = runTest {
        val bridge = RecordingItemBridge()
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<List<*>>()

        val job = collect(repository.observeItems("list-1"), emissions)
        bridge.emitItems(listOf(activeDocument("item-1")))
        job.cancelAndJoin()
        val afterCancellation = emissions.size

        bridge.emitItems(listOf(activeDocument("item-1"), activeDocument("item-2")))

        assertEquals(afterCancellation, emissions.size)
    }

    // --- listener lifecycle: observeItem -------------------------------------------------

    @Test
    fun collectingASingleItemRegistersExactlyOneListenerAndReleasesItOnCancellation() = runTest {
        val bridge = RecordingItemBridge()
        val repository = repositoryFor(bridge)

        val job = collect(repository.observeItem("list-1", "item-1"), mutableListOf())
        assertEquals(1, bridge.itemAddCount)
        assertTrue(bridge.hasLiveItemListener)

        job.cancelAndJoin()

        assertEquals(1, bridge.itemRemoveCount)
        assertFalse(bridge.hasLiveItemListener)
    }

    @Test
    fun aMissingItemEmitsNull() = runTest {
        val bridge = RecordingItemBridge()
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<Any?>()

        val job = collect(repository.observeItem("list-1", "item-1"), emissions)
        bridge.emitItem(null)

        assertNull(emissions.last())
        job.cancelAndJoin()
    }

    // --- errors close the flow ------------------------------------------------------------

    @Test
    fun aSnapshotErrorClosesTheItemsFlowWithAMappedException() = runTest {
        val bridge = RecordingItemBridge()
        val repository = repositoryFor(bridge)
        var caught: Throwable? = null

        val job = (this as CoroutineScope).launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                repository.observeItems("list-1").toList(mutableListOf())
            } catch (throwable: Throwable) {
                caught = throwable
            }
        }
        bridge.emitItemsError(firestoreError("permission-denied"))
        job.join()

        val failure = assertIs<RepositoryException>(caught)
        assertEquals(RepositoryErrorCode.FORBIDDEN, failure.error.code)
    }

    // --- delegation: tombstone filtering + ordering ---------------------------------

    @Test
    fun tombstonedItemsAreFilteredAndSurvivorsAreOrderedByCreatedAtThenId() = runTest {
        val bridge = RecordingItemBridge()
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<List<com.fluxit.domain.FluxItem>>()

        val job = collect(repository.observeItems("list-1"), emissions)
        bridge.emitItems(
            listOf(
                activeDocument("item-b", title = "B", createdAt = 2_000L),
                tombstonedDocument("item-deleted", createdAt = 500L),
                activeDocument("item-a", title = "A", createdAt = 1_000L),
            )
        )

        val visible = emissions.last().map { it.title }
        assertEquals(listOf("A", "B"), visible, "tombstoned entries filtered, survivors ordered by createdAt")

        job.cancelAndJoin()
    }

    // --- ObserveItemsSnapshot - real isFromCache/hasPendingWrites --------------
    //
    // Same rationale as WebFirebaseListRepositoryTest's parallel block: proves
    // WebFirebaseItemRepository.observeItemsSnapshot correctly maps whatever
    // WebFirestoreItemSnapshot the bridge hands it into RepositorySnapshot, on its own
    // listener registration, independent of observeItems. Does NOT prove the real JS
    // FirebaseItemBridge.observeItemsSnapshot receives real metadata from a live SDK -
    // that is the emulator-backed integration check's job.

    @Test
    fun observeItemsSnapshotRegistersItsOwnListenerIndependentOfObserveItems() = runTest {
        val bridge = RecordingItemBridge()
        val repository = repositoryFor(bridge)

        val job = collect(repository.observeItemsSnapshot("list-1"), mutableListOf())

        assertEquals(1, bridge.itemsSnapshotAddCount)
        assertTrue(bridge.hasLiveItemsSnapshotListener)
        assertEquals(0, bridge.itemsAddCount, "must not also register the plain observeItems listener")

        job.cancelAndJoin()

        assertEquals(1, bridge.itemsSnapshotRemoveCount)
        assertFalse(bridge.hasLiveItemsSnapshotListener, "the Firestore listener must be released")
    }

    @Test
    fun observeItemsSnapshotCarriesTheRealIsFromCacheAndHasPendingWritesFlags() = runTest {
        val bridge = RecordingItemBridge()
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<com.fluxit.domain.RepositorySnapshot<List<com.fluxit.domain.FluxItem>>>()

        val job = collect(repository.observeItemsSnapshot("list-1"), emissions)

        bridge.emitItemsSnapshot(listOf(activeDocument("item-1")), isFromCache = true, hasPendingWrites = true)
        val cached = emissions.last()
        assertTrue(cached.isFromCache)
        assertTrue(cached.hasPendingWrites)
        assertEquals(listOf("item-1"), cached.value.map { it.id })

        bridge.emitItemsSnapshot(listOf(activeDocument("item-1")), isFromCache = false, hasPendingWrites = false)
        val fresh = emissions.last()
        assertFalse(fresh.isFromCache)
        assertFalse(fresh.hasPendingWrites)

        job.cancelAndJoin()
    }

    @Test
    fun aSnapshotErrorClosesTheItemsSnapshotFlowWithAMappedException() = runTest {
        val bridge = RecordingItemBridge()
        val repository = repositoryFor(bridge)
        var caught: Throwable? = null

        val job = (this as CoroutineScope).launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                repository.observeItemsSnapshot("list-1").toList(mutableListOf())
            } catch (throwable: Throwable) {
                caught = throwable
            }
        }
        bridge.emitItemsSnapshotError(firestoreError("permission-denied"))
        job.join()

        val failure = assertIs<RepositoryException>(caught)
        assertEquals(RepositoryErrorCode.FORBIDDEN, failure.error.code)
    }

    // --- addItem: whole-document write on a brand-new document (exempt from field patches) -----------------

    @Test
    fun addItemWritesTheFullInitialFieldSet() = runTest {
        val bridge = RecordingItemBridge()
        val repository = repositoryFor(bridge, FixedUidProvider("uid-42"))

        repository.addItem("list-1", "Milk")

        val call = bridge.addCalls.single()
        assertEquals("uid-42", call.uid)
        assertEquals("list-1", call.listId)
        assertEquals(
            setOf(
                FirebaseSchema.Fields.LIST_ID,
                FirebaseSchema.Fields.TITLE,
                FirebaseSchema.Fields.DESCRIPTION,
                FirebaseSchema.Fields.IS_COMPLETED,
                FirebaseSchema.Fields.PHOTO_REF,
                FirebaseSchema.Fields.CREATED_AT,
                FirebaseSchema.Fields.UPDATED_AT,
                FirebaseSchema.Fields.DELETED_AT,
                FirebaseSchema.Fields.SCHEMA_VERSION,
            ),
            call.fields.keys,
            "addItem must write the full initial field set, not a partial patch",
        )
        assertEquals(FirebaseValue.Text("Milk"), call.fields[FirebaseSchema.Fields.TITLE])
        assertEquals(FirebaseValue.Bool(false), call.fields[FirebaseSchema.Fields.IS_COMPLETED])
        assertEquals(FirebaseValue.Null, call.fields[FirebaseSchema.Fields.DELETED_AT])
    }

    @Test
    fun aFailedAddThrowsTheMappedException() = runTest {
        val bridge = RecordingItemBridge()
        bridge.addFailure = firestoreError("unauthenticated")
        val repository = repositoryFor(bridge)

        val failure = assertFailsWith<RepositoryException> {
            repository.addItem("list-1", "Milk")
        }
        assertEquals(RepositoryErrorCode.SESSION_REQUIRED, failure.error.code)
    }

    // --- updateItem/setPhotoRef: field-scoped patches only, no counters ----------

    @Test
    fun updateItemSendsOnlyTheChangedFieldsNeverCounters() = runTest {
        val bridge = RecordingItemBridge()
        val repository = repositoryFor(bridge, FixedUidProvider("uid-1"))

        repository.updateItem("list-1", "item-1", "Whole Milk", "2%")

        val call = bridge.updateCalls.single()
        assertEquals("uid-1", call.uid)
        assertEquals("list-1", call.listId)
        assertEquals("item-1", call.itemId)
        assertEquals(
            setOf(FirebaseSchema.Fields.TITLE, FirebaseSchema.Fields.DESCRIPTION, FirebaseSchema.Fields.UPDATED_AT),
            call.fields.keys,
            "updateItem must be a field-scoped patch - it must never carry totalItems/completedItems",
        )
    }

    @Test
    fun setPhotoRefTouchesOnlyPhotoRefAndCanClearItToNull() = runTest {
        val bridge = RecordingItemBridge()
        val repository = repositoryFor(bridge)

        repository.setPhotoRef("list-1", "item-1", "users/uid/items/item-1/photo-1")
        assertEquals(
            FirebaseValue.Text("users/uid/items/item-1/photo-1"),
            bridge.updateCalls.single().fields[FirebaseSchema.Fields.PHOTO_REF],
        )

        repository.setPhotoRef("list-1", "item-1", null)
        assertEquals(FirebaseValue.Null, bridge.updateCalls.last().fields[FirebaseSchema.Fields.PHOTO_REF])
        assertEquals(setOf(FirebaseSchema.Fields.PHOTO_REF, FirebaseSchema.Fields.UPDATED_AT), bridge.updateCalls.last().fields.keys)
    }

    @Test
    fun aFailedUpdateThrowsTheMappedException() = runTest {
        val bridge = RecordingItemBridge()
        bridge.updateFailure = firestoreError("permission-denied")
        val repository = repositoryFor(bridge)

        val failure = assertFailsWith<RepositoryException> {
            repository.updateItem("list-1", "item-1", "x", null)
        }
        assertEquals(RepositoryErrorCode.FORBIDDEN, failure.error.code)
    }

    // --- setCompleted: idempotent read-then-decide counter policy --------------------------

    @Test
    fun setCompletedTrueOnAnActiveIncompleteItemIncrementsCompletedItems() = runTest {
        val bridge = RecordingItemBridge()
        bridge.currentFieldsForCounterMutation = activeFields(completed = false)
        val repository = repositoryFor(bridge)

        repository.setCompleted("list-1", "item-1", true)

        val outcome = assertIs<ItemCounterOutcome.ApplyPatch>(bridge.counterMutationCalls.single().outcome)
        assertEquals(FirebaseValue.Bool(true), outcome.itemFields[FirebaseSchema.Fields.IS_COMPLETED])
        assertEquals(FirebaseValue.Number(1), outcome.counterDelta[FirebaseSchema.Fields.COMPLETED_ITEMS])
    }

    @Test
    fun repeatedSetCompletedTrueOnAnAlreadyCompletedItemIsIdempotent() = runTest {
        val bridge = RecordingItemBridge()
        bridge.currentFieldsForCounterMutation = activeFields(completed = true) // already true
        val repository = repositoryFor(bridge)

        repository.setCompleted("list-1", "item-1", true)

        val outcome = assertIs<ItemCounterOutcome.ApplyPatch>(bridge.counterMutationCalls.single().outcome)
        assertTrue(outcome.counterDelta.isEmpty(), "a repeated setCompleted(true) must compute a zero delta, not double-increment")
    }

    @Test
    fun setCompletedTrueOnATombstonedItemNeverTouchesCounters() = runTest {
        val bridge = RecordingItemBridge()
        bridge.currentFieldsForCounterMutation = tombstonedFields(completed = false)
        val repository = repositoryFor(bridge)

        repository.setCompleted("list-1", "item-1", true)

        val outcome = assertIs<ItemCounterOutcome.ApplyPatch>(bridge.counterMutationCalls.single().outcome)
        assertTrue(outcome.counterDelta.isEmpty(), "a tombstoned item is already excluded from both counters")
    }

    @Test
    fun setCompletedOnAMissingItemIsASilentNoOp() = runTest {
        val bridge = RecordingItemBridge()
        bridge.currentFieldsForCounterMutation = null
        val repository = repositoryFor(bridge)

        repository.setCompleted("list-1", "item-1", true) // must not throw

        assertEquals(ItemCounterOutcome.NoOp, bridge.counterMutationCalls.single().outcome)
    }

    // --- softDeleteItem / restoreItem: idempotent, scoped to active+completed --------------

    @Test
    fun softDeleteItemDecrementsBothCountersWhenTheItemWasActiveAndCompleted() = runTest {
        val bridge = RecordingItemBridge()
        bridge.currentFieldsForCounterMutation = activeFields(completed = true)
        val repository = repositoryFor(bridge)

        repository.softDeleteItem("list-1", "item-1")

        val outcome = assertIs<ItemCounterOutcome.ApplyPatch>(bridge.counterMutationCalls.single().outcome)
        assertEquals(FirebaseValue.Number(-1), outcome.counterDelta[FirebaseSchema.Fields.TOTAL_ITEMS])
        assertEquals(FirebaseValue.Number(-1), outcome.counterDelta[FirebaseSchema.Fields.COMPLETED_ITEMS])
    }

    @Test
    fun softDeleteItemOnAnActiveIncompleteItemOnlyDecrementsTotal() = runTest {
        val bridge = RecordingItemBridge()
        bridge.currentFieldsForCounterMutation = activeFields(completed = false)
        val repository = repositoryFor(bridge)

        repository.softDeleteItem("list-1", "item-1")

        val outcome = assertIs<ItemCounterOutcome.ApplyPatch>(bridge.counterMutationCalls.single().outcome)
        assertEquals(FirebaseValue.Number(-1), outcome.counterDelta[FirebaseSchema.Fields.TOTAL_ITEMS])
        assertFalse(FirebaseSchema.Fields.COMPLETED_ITEMS in outcome.counterDelta)
    }

    @Test
    fun repeatedSoftDeleteItemCallsAreIdempotentAndDoNotDoubleDecrement() = runTest {
        val bridge = RecordingItemBridge()
        bridge.currentFieldsForCounterMutation = tombstonedFields(completed = true) // already tombstoned
        val repository = repositoryFor(bridge)

        repository.softDeleteItem("list-1", "item-1")

        val outcome = assertIs<ItemCounterOutcome.ApplyPatch>(bridge.counterMutationCalls.single().outcome)
        assertTrue(outcome.counterDelta.isEmpty(), "an already-tombstoned item must not be decremented again")
    }

    @Test
    fun restoreItemIncrementsBothCountersWhenTheItemWasTombstonedAndCompleted() = runTest {
        val bridge = RecordingItemBridge()
        bridge.currentFieldsForCounterMutation = tombstonedFields(completed = true)
        val repository = repositoryFor(bridge)

        repository.restoreItem("list-1", "item-1")

        val outcome = assertIs<ItemCounterOutcome.ApplyPatch>(bridge.counterMutationCalls.single().outcome)
        assertEquals(FirebaseValue.Null, outcome.itemFields[FirebaseSchema.Fields.DELETED_AT])
        assertEquals(FirebaseValue.Number(1), outcome.counterDelta[FirebaseSchema.Fields.TOTAL_ITEMS])
        assertEquals(FirebaseValue.Number(1), outcome.counterDelta[FirebaseSchema.Fields.COMPLETED_ITEMS])
    }

    @Test
    fun restoreItemOnAnAlreadyActiveItemIsANoOpForCounters() = runTest {
        val bridge = RecordingItemBridge()
        bridge.currentFieldsForCounterMutation = activeFields(completed = false) // never tombstoned
        val repository = repositoryFor(bridge)

        repository.restoreItem("list-1", "item-1")

        val outcome = assertIs<ItemCounterOutcome.ApplyPatch>(bridge.counterMutationCalls.single().outcome)
        assertTrue(outcome.counterDelta.isEmpty())
    }

    // --- deleteItem: genuine hard delete, idempotent w.r.t. a prior soft delete ------------

    @Test
    fun deleteItemHardDeletesAndDecrementsCountersWhenTheItemWasActive() = runTest {
        val bridge = RecordingItemBridge()
        bridge.currentFieldsForCounterMutation = activeFields(completed = true)
        val repository = repositoryFor(bridge)

        repository.deleteItem("list-1", "item-1")

        val outcome = assertIs<ItemCounterOutcome.HardDelete>(bridge.counterMutationCalls.single().outcome)
        assertEquals(FirebaseValue.Number(-1), outcome.counterDelta[FirebaseSchema.Fields.TOTAL_ITEMS])
        assertEquals(FirebaseValue.Number(-1), outcome.counterDelta[FirebaseSchema.Fields.COMPLETED_ITEMS])
    }

    @Test
    fun deleteItemAfterAPriorSoftDeleteDoesNotDoubleDecrementCounters() = runTest {
        val bridge = RecordingItemBridge()
        bridge.currentFieldsForCounterMutation = tombstonedFields(completed = true) // already decremented by softDeleteItem
        val repository = repositoryFor(bridge)

        repository.deleteItem("list-1", "item-1")

        val outcome = assertIs<ItemCounterOutcome.HardDelete>(bridge.counterMutationCalls.single().outcome)
        assertTrue(outcome.counterDelta.isEmpty(), "must not decrement a second time")
    }

    @Test
    fun deleteItemOnAMissingItemIsASilentNoOp() = runTest {
        val bridge = RecordingItemBridge()
        bridge.currentFieldsForCounterMutation = null
        val repository = repositoryFor(bridge)

        repository.deleteItem("list-1", "never-existed") // must not throw

        assertEquals(ItemCounterOutcome.NoOp, bridge.counterMutationCalls.single().outcome)
    }

    @Test
    fun aFailedCounterMutationThrowsTheMappedException() = runTest {
        val bridge = RecordingItemBridge()
        bridge.currentFieldsForCounterMutation = activeFields()
        bridge.counterMutationFailure = firestoreError("unavailable")
        val repository = repositoryFor(bridge)

        val failure = assertFailsWith<RepositoryException> {
            repository.setCompleted("list-1", "item-1", true)
        }
        assertEquals(RepositoryErrorCode.OFFLINE, failure.error.code)
    }

    // --- clearCompleted: chunked, resumable loop over the bridge's per-page contract -------

    @Test
    fun clearCompletedStopsAfterASinglePageWhenNothingRemains() = runTest {
        val bridge = RecordingItemBridge()
        bridge.clearChunkResultQueue.addAll(listOf(0))
        val repository = repositoryFor(bridge, clearCompletedChunkSize = 400)

        repository.clearCompleted("list-1")

        val call = bridge.clearChunkCalls.single()
        assertEquals(400, call.chunkSize)
        assertEquals("list-1", call.listId)
    }

    @Test
    fun clearCompletedLoopsUntilAnEmptyPageProvingTheMultiChunkStrategy() = runTest {
        val bridge = RecordingItemBridge()
        bridge.clearChunkResultQueue.addAll(listOf(5, 5, 4)) // a 14-item sweep, chunkSize=5 -> 5+5+4, then one empty page
        val repository = repositoryFor(bridge, clearCompletedChunkSize = 5)

        repository.clearCompleted("list-1")

        assertEquals(4, bridge.clearChunkCalls.size, "3 non-empty pages plus the terminating empty page")
        assertTrue(bridge.clearChunkCalls.all { it.chunkSize == 5 })
    }

    @Test
    fun aFailedClearCompletedChunkThrowsTheMappedException() = runTest {
        val bridge = RecordingItemBridge()
        bridge.clearChunkFailure = firestoreError("resource-exhausted")
        val repository = repositoryFor(bridge)

        val failure = assertFailsWith<RepositoryException> {
            repository.clearCompleted("list-1")
        }
        assertEquals(RepositoryErrorCode.QUOTA, failure.error.code)
    }

    // --- session required ------------------------------------------------------------------

    @Test
    fun aMutationWithNoSignedInUserFailsBeforeTouchingTheBridge() = runTest {
        val bridge = RecordingItemBridge()
        val repository = repositoryFor(bridge, FixedUidProvider(uid = null))

        val failure = assertFailsWith<RepositoryException> {
            repository.addItem("list-1", "Milk")
        }
        assertEquals(RepositoryErrorCode.SESSION_REQUIRED, failure.error.code)
        assertTrue(bridge.addCalls.isEmpty())
    }
}
