package com.fluxit.firebase.list

import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.data.remote.FirebaseValue
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
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
 * FB-203 unit tests for the iOS list adapter's own logic: the `callbackFlow` listener
 * lifecycle over the Swift bridge, delegation of ordering/tombstone-filtering to FB-201's
 * [com.fluxit.data.remote.FirebaseDocumentMapper], and the field-scoped-patch-vs-whole-
 * document-set shape required by `DEC-003d`/`DEC-003d-1`. The Swift bridge (and
 * therefore the Firebase Apple SDK behind it) is replaced by [RecordingListBridge]; the
 * adapter code under test is the production code.
 *
 * Deliberately the same test matrix as FB-202's `AndroidFirebaseListRepository`
 * coverage where a Gradle-runnable equivalent exists, so a behavioural divergence
 * between the two platforms shows up as a failing test. What this file does NOT prove:
 * that the real Firebase Apple SDK actually reports snapshots/errors the way assumed
 * here, and that the Swift implementation of [IosFirestoreListBridge] honours the
 * protocol - that is what the emulator-backed integration check
 * (`IosFirestoreListIntegrationCheck`) exercises.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosFirebaseListRepositoryTest {

    private fun repositoryFor(
        bridge: IosFirestoreListBridge,
        uid: CurrentUidProvider = FixedUidProvider(),
    ): IosFirebaseListRepository = IosFirebaseListRepository({ bridge }, uid)

    private fun <T> TestScope.collect(
        flow: kotlinx.coroutines.flow.Flow<T>,
        into: MutableList<T>,
    ): Job = (this as CoroutineScope).launch(UnconfinedTestDispatcher(testScheduler)) {
        flow.toList(into)
    }

    private fun activeDocument(
        id: String,
        name: String = "Groceries",
        createdAt: Long = 1_000L,
        updatedAt: Long = 1_000L,
        total: Long = 0,
        completed: Long = 0,
    ): IosFirestoreListDocument = IosFirestoreListDocument(
        id = id,
        fields = mapOf(
            FirebaseSchema.Fields.NAME to FirebaseValue.Text(name),
            FirebaseSchema.Fields.ICON to FirebaseValue.Text(ListIcon.CART.name),
            FirebaseSchema.Fields.COLOR to FirebaseValue.Text(ListColor.PRIMARY_BLUE.name),
            FirebaseSchema.Fields.CREATED_AT to FirebaseValue.Timestamp(createdAt),
            FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.Timestamp(updatedAt),
            FirebaseSchema.Fields.DELETED_AT to FirebaseValue.Null,
            FirebaseSchema.Fields.TOTAL_ITEMS to FirebaseValue.Number(total),
            FirebaseSchema.Fields.COMPLETED_ITEMS to FirebaseValue.Number(completed),
            FirebaseSchema.Fields.SCHEMA_VERSION to FirebaseValue.Number(FirebaseSchema.CURRENT_VERSION),
        ),
    )

    private fun tombstonedDocument(id: String, createdAt: Long = 1_000L): IosFirestoreListDocument =
        IosFirestoreListDocument(
            id = id,
            fields = activeDocument(id, createdAt = createdAt).fields +
                (FirebaseSchema.Fields.DELETED_AT to FirebaseValue.Timestamp(2_000L)),
        )

    // --- listener lifecycle: observeListSummaries -------------------------------------

    @Test
    fun collectingSummariesRegistersExactlyOneListener() = runTest {
        val bridge = RecordingListBridge()
        val repository = repositoryFor(bridge)

        val job = collect(repository.observeListSummaries(), mutableListOf())

        assertEquals(1, bridge.summariesAddCount)
        assertTrue(bridge.hasLiveSummariesListener)
        assertEquals(0, bridge.summariesRemoveCount)

        job.cancelAndJoin()
    }

    @Test
    fun cancellingTheSummariesCollectorRemovesTheUnderlyingListener() = runTest {
        val bridge = RecordingListBridge()
        val repository = repositoryFor(bridge)

        val job = collect(repository.observeListSummaries(), mutableListOf())
        job.cancelAndJoin()

        assertEquals(1, bridge.summariesRemoveCount)
        assertFalse(bridge.hasLiveSummariesListener, "the Firestore listener must be released")
    }

    @Test
    fun aReleasedSummariesListenerStopsReachingTheCollector() = runTest {
        val bridge = RecordingListBridge()
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<List<*>>()

        val job = collect(repository.observeListSummaries(), emissions)
        bridge.emitSummaries(listOf(activeDocument("list-1")))
        job.cancelAndJoin()
        val afterCancellation = emissions.size

        // A real, subsequent snapshot: if the listener had leaked, this would still be
        // delivered and the emission list would grow.
        bridge.emitSummaries(listOf(activeDocument("list-1"), activeDocument("list-2")))

        assertEquals(afterCancellation, emissions.size)
    }

    @Test
    fun eachSummariesCollectorOwnsItsOwnListenerAndReleasesIt() = runTest {
        val bridge = RecordingListBridge()
        val repository = repositoryFor(bridge)

        val first = collect(repository.observeListSummaries(), mutableListOf())
        assertEquals(1, bridge.summariesAddCount)

        first.cancelAndJoin()
        assertEquals(1, bridge.summariesRemoveCount)
        assertFalse(bridge.hasLiveSummariesListener)
    }

    // --- listener lifecycle: observeList -----------------------------------------------

    @Test
    fun collectingASingleListRegistersExactlyOneListenerAndReleasesItOnCancellation() = runTest {
        val bridge = RecordingListBridge()
        val repository = repositoryFor(bridge)

        val job = collect(repository.observeList("list-1"), mutableListOf())
        assertEquals(1, bridge.documentAddCount)
        assertTrue(bridge.hasLiveDocumentListener)

        job.cancelAndJoin()

        assertEquals(1, bridge.documentRemoveCount)
        assertFalse(bridge.hasLiveDocumentListener)
    }

    @Test
    fun aMissingDocumentEmitsNull() = runTest {
        val bridge = RecordingListBridge()
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<Any?>()

        val job = collect(repository.observeList("list-1"), emissions)
        bridge.emitDocument(null)

        assertNull(emissions.last())
        job.cancelAndJoin()
    }

    // --- errors close the flow ----------------------------------------------------------

    @Test
    fun aSnapshotErrorClosesTheSummariesFlowWithAMappedException() = runTest {
        val bridge = RecordingListBridge()
        val repository = repositoryFor(bridge)
        var caught: Throwable? = null

        val job = (this as CoroutineScope).launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                repository.observeListSummaries().toList(mutableListOf())
            } catch (throwable: Throwable) {
                caught = throwable
            }
        }
        bridge.emitSummariesError(firestoreError(7L)) // permissionDenied
        job.join()

        val failure = assertIs<ListRepositoryException>(caught)
        assertEquals(RepositoryErrorCode.FORBIDDEN, failure.error.code)
    }

    // --- FB-201 delegation: tombstone filtering + ordering -----------------------------

    @Test
    fun tombstonedDocumentsAreFilteredAndSurvivorsAreOrderedByCreatedAtThenId() = runTest {
        val bridge = RecordingListBridge()
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<List<com.fluxit.domain.FluxListSummary>>()

        val job = collect(repository.observeListSummaries(), emissions)
        bridge.emitSummaries(
            listOf(
                activeDocument("list-b", createdAt = 2_000L),
                tombstonedDocument("list-deleted", createdAt = 500L),
                activeDocument("list-a", createdAt = 1_000L),
            )
        )

        val visible = emissions.last().map { it.list.id }
        assertEquals(listOf("list-a", "list-b"), visible, "tombstoned entries filtered, survivors ordered by createdAt")

        job.cancelAndJoin()
    }

    // --- createList: DEC-003d-1 whole-document set on a brand-new document -------------

    @Test
    fun createListWritesTheFullInitialFieldSetAndReturnsTheGeneratedId() = runTest {
        val bridge = RecordingListBridge()
        bridge.createdIdOverride = "new-list-id"
        val repository = repositoryFor(bridge, FixedUidProvider("uid-42"))

        val id = repository.createList("Groceries", ListIcon.CART, ListColor.PRIMARY_BLUE)

        assertEquals("new-list-id", id)
        val call = bridge.createCalls.single()
        assertEquals("uid-42", call.uid)
        assertEquals(
            setOf(
                FirebaseSchema.Fields.NAME,
                FirebaseSchema.Fields.ICON,
                FirebaseSchema.Fields.COLOR,
                FirebaseSchema.Fields.CREATED_AT,
                FirebaseSchema.Fields.UPDATED_AT,
                FirebaseSchema.Fields.DELETED_AT,
                FirebaseSchema.Fields.TOTAL_ITEMS,
                FirebaseSchema.Fields.COMPLETED_ITEMS,
                FirebaseSchema.Fields.SCHEMA_VERSION,
            ),
            call.fields.keys,
            "createList must write the full initial field set (DEC-003d-1), not a partial patch",
        )
        assertEquals(FirebaseValue.Text("Groceries"), call.fields[FirebaseSchema.Fields.NAME])
        assertEquals(FirebaseValue.Number(0), call.fields[FirebaseSchema.Fields.TOTAL_ITEMS])
        assertEquals(FirebaseValue.Null, call.fields[FirebaseSchema.Fields.DELETED_AT])
    }

    @Test
    fun aFailedCreateThrowsTheMappedException() = runTest {
        val bridge = RecordingListBridge()
        bridge.createFailure = firestoreError(16L) // unauthenticated
        val repository = repositoryFor(bridge)

        val failure = assertFailsWith<ListRepositoryException> {
            repository.createList("Groceries", ListIcon.CART, ListColor.PRIMARY_BLUE)
        }
        assertEquals(RepositoryErrorCode.SESSION_REQUIRED, failure.error.code)
    }

    // --- update/softDelete/restore: DEC-003d field-scoped patches only ------------------

    @Test
    fun updateListSendsOnlyTheChangedFieldsNeverTheWholeDocument() = runTest {
        val bridge = RecordingListBridge()
        val repository = repositoryFor(bridge, FixedUidProvider("uid-1"))

        repository.updateList("list-1", "Renamed", ListIcon.CART, ListColor.PRIMARY_BLUE)

        val call = bridge.updateCalls.single()
        assertEquals("uid-1", call.uid)
        assertEquals("list-1", call.listId)
        assertEquals(
            setOf(
                FirebaseSchema.Fields.NAME,
                FirebaseSchema.Fields.ICON,
                FirebaseSchema.Fields.COLOR,
                FirebaseSchema.Fields.UPDATED_AT,
            ),
            call.fields.keys,
            "updateList must be a field-scoped patch (DEC-003d) - it must never carry totalItems/completedItems",
        )
    }

    @Test
    fun softDeleteListTouchesOnlyDeletedAt() = runTest {
        val bridge = RecordingListBridge()
        val repository = repositoryFor(bridge)

        repository.softDeleteList("list-1")

        val call = bridge.updateCalls.single()
        assertEquals(setOf(FirebaseSchema.Fields.DELETED_AT), call.fields.keys)
        assertEquals(FirebaseValue.PendingServerTimestamp, call.fields[FirebaseSchema.Fields.DELETED_AT])
    }

    @Test
    fun restoreListTouchesOnlyDeletedAtClearingIt() = runTest {
        val bridge = RecordingListBridge()
        val repository = repositoryFor(bridge)

        repository.restoreList("list-1")

        val call = bridge.updateCalls.single()
        assertEquals(setOf(FirebaseSchema.Fields.DELETED_AT), call.fields.keys)
        assertEquals(FirebaseValue.Null, call.fields[FirebaseSchema.Fields.DELETED_AT])
    }

    @Test
    fun aFailedUpdateThrowsTheMappedException() = runTest {
        val bridge = RecordingListBridge()
        bridge.updateFailure = firestoreError(7L) // permissionDenied
        val repository = repositoryFor(bridge)

        val failure = assertFailsWith<ListRepositoryException> {
            repository.updateList("list-1", "x", ListIcon.CART, ListColor.PRIMARY_BLUE)
        }
        assertEquals(RepositoryErrorCode.FORBIDDEN, failure.error.code)
    }

    // --- purgeExpired: DEC-003c documented no-op ----------------------------------------

    @Test
    fun purgeExpiredNeverTouchesTheBridge() = runTest {
        val bridge = RecordingListBridge()
        val repository = repositoryFor(bridge)

        repository.purgeExpired()

        assertTrue(bridge.createCalls.isEmpty())
        assertTrue(bridge.updateCalls.isEmpty())
        assertEquals(0, bridge.summariesAddCount)
        assertEquals(0, bridge.documentAddCount)
    }

    // --- session required ----------------------------------------------------------------

    @Test
    fun aMutationWithNoSignedInUserFailsBeforeTouchingTheBridge() = runTest {
        val bridge = RecordingListBridge()
        val repository = repositoryFor(bridge, FixedUidProvider(uid = null))

        val failure = assertFailsWith<ListRepositoryException> {
            repository.createList("Groceries", ListIcon.CART, ListColor.PRIMARY_BLUE)
        }
        assertEquals(RepositoryErrorCode.SESSION_REQUIRED, failure.error.code)
        assertTrue(bridge.createCalls.isEmpty())
    }
}
