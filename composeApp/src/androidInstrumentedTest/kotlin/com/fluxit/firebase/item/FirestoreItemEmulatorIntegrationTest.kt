package com.fluxit.firebase.item

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fluxit.config.FirebaseEmulatorConfig
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.domain.FluxItem
import com.fluxit.firebase.list.CurrentUidProvider
import com.fluxit.firebase.list.ListRepositoryException
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FB-204 Android integration checks: the real Firebase Android Firestore SDK, driven
 * through [AndroidFirebaseItemRepository], against a real Firestore emulator - the same
 * emulator [com.fluxit.firebase.list.FirestoreListEmulatorIntegrationTest] (FB-202)
 * exercises, reused rather than re-provisioned.
 *
 * Every document this suite writes lives only in the emulator's in-memory store.
 * Authentication is real (Auth emulator), because `firestore.rules` is owner-only.
 *
 * A list document is bootstrapped directly (not through
 * [com.fluxit.firebase.list.AndroidFirebaseListRepository]) so this file stays a
 * self-contained FB-204 artifact rather than depending on FB-202's class for setup;
 * the fields written are exactly what [AndroidFirebaseItemRepository]'s counter
 * transactions read/write (`totalItems`/`completedItems`), proven correct by FB-202's
 * own suite already.
 *
 * Prerequisites: same Auth+Firestore emulators as
 * `FirestoreListEmulatorIntegrationTest`'s KDoc documents. Run with:
 * `./gradlew :composeApp:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class FirestoreItemEmulatorIntegrationTest {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var uid: String
    private lateinit var repository: AndroidFirebaseItemRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun connectToTheEmulatorsAndSignIn(): Unit = runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val app = FirebaseApp.getApps(context)
            .firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    // Throwaway values: both emulators accept any key/app id, and a
                    // `demo-` project id can never resolve to a real Firebase project.
                    .setApiKey("fb204-instrumented-test-key")
                    .setApplicationId("1:0:android:fb204")
                    .setProjectId("demo-fluxit")
                    .build(),
                APP_NAME,
            )
        auth = FirebaseAuth.getInstance(app).apply {
            if (!authEmulatorConfigured) {
                useEmulator(emulatorHost(), FirebaseEmulatorConfig.AUTH_PORT)
                authEmulatorConfigured = true
            }
            signOut()
        }
        firestore = FirebaseFirestore.getInstance(app).apply {
            if (!firestoreEmulatorConfigured) {
                useEmulator(emulatorHost(), FirebaseEmulatorConfig.FIRESTORE_PORT)
                firestoreEmulatorConfigured = true
            }
            // Defensive: an earlier test in this class (or a prior run reusing the same
            // named FirebaseApp) may have left the network disabled - see the offline
            // test's `finally`. A stuck-offline SDK would silently hang every later
            // `Task` this suite awaits, so this is re-asserted at the top of every test.
            enableNetwork().awaitResult()
        }
        auth.createUserWithEmailAndPassword(uniqueEmail(), PASSWORD).awaitResult()
        uid = requireNotNull(auth.currentUser?.uid) { "sign-up did not resolve a uid" }
        repository = AndroidFirebaseItemRepository(firestore, CurrentUidProvider { uid })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun signOutAndRelease() {
        auth.signOut()
        scope.cancel()
    }

    // --- add / observe / ordering -----------------------------------------------------

    @Test
    fun addItemWritesTheFullInitialSchemaAndIncrementsTotalItems(): Unit = runBlocking {
        val listId = bootstrapList()

        repository.addItem(listId, "Milk")

        val snapshot = itemsCollection(listId).get().awaitResult()
        assertEquals(1, snapshot.size())
        val raw = snapshot.documents.first()
        assertEquals(listId, raw.getString("listId"))
        assertEquals("Milk", raw.getString("title"))
        assertEquals(false, raw.getBoolean("isCompleted"))
        assertNotNull(raw.getTimestamp("createdAt"))
        assertNotNull(raw.getTimestamp("updatedAt"))
        assertTrue(raw.contains("deletedAt"))
        assertNull(raw.get("deletedAt"))
        assertTrue(raw.contains("description"))
        assertNull(raw.get("description"))
        assertEquals(1L, raw.getLong("schemaVersion"))

        val listRaw = listDoc(listId).get().awaitResult()
        assertEquals(1L, listRaw.getLong("totalItems"))
        assertEquals(0L, listRaw.getLong("completedItems"))
    }

    @Test
    fun observeItemsOrdersByCreatedAtThenDocumentIdAndFiltersTombstones(): Unit = runBlocking {
        val listId = bootstrapList()
        repository.addItem(listId, "A")
        repository.addItem(listId, "B")
        repository.addItem(listId, "C")

        val items = waitForItems(listId, 3)
        assertEquals(listOf("A", "B", "C"), items.map { it.title })
        assertTrue(
            items.zipWithNext().all { (a, b) -> a.createdAt <= b.createdAt },
            "expected non-decreasing createdAt order, got ${items.map { it.createdAt }}",
        )

        val toDelete = items[1].id
        repository.softDeleteItem(listId, toDelete)

        val after = withTimeout(TIMEOUT_MS) { repository.observeItems(listId).first { it.none { i -> i.id == toDelete } } }
        assertEquals(listOf("A", "C"), after.map { it.title })
    }

    @Test
    fun observeItemReturnsNullForATombstonedItem(): Unit = runBlocking {
        val listId = bootstrapList()
        repository.addItem(listId, "Milk")
        val itemId = waitForItems(listId, 1).single().id

        assertNotNull(withTimeout(TIMEOUT_MS) { repository.observeItem(listId, itemId).first { it != null } })

        repository.softDeleteItem(listId, itemId)

        val afterDelete = withTimeout(TIMEOUT_MS) { repository.observeItem(listId, itemId).first { it == null } }
        assertNull(afterDelete)
    }

    // --- field-scoped mutation (DEC-003d), no counter involvement ---------------------

    @Test
    fun updateItemPatchesOnlyItsOwnFieldsAndNeverTouchesCounters(): Unit = runBlocking {
        val listId = bootstrapList()
        repository.addItem(listId, "Milk")
        val itemId = waitForItems(listId, 1).single().id
        // Simulate a concurrent counter update this call must not clobber.
        listDoc(listId).update(mapOf("totalItems" to 5L, "completedItems" to 2L)).awaitResult()

        repository.updateItem(listId, itemId, "Whole Milk", "2%")

        val raw = itemsCollection(listId).document(itemId).get().awaitResult()
        assertEquals("Whole Milk", raw.getString("title"))
        assertEquals("2%", raw.getString("description"))
        val listRaw = listDoc(listId).get().awaitResult()
        assertEquals(5L, listRaw.getLong("totalItems"), "a field-scoped patch must not clobber an untouched field")
        assertEquals(2L, listRaw.getLong("completedItems"), "a field-scoped patch must not clobber an untouched field")
    }

    @Test
    fun setPhotoRefPatchesOnlyThatFieldAndClearsBackToNull(): Unit = runBlocking {
        val listId = bootstrapList()
        repository.addItem(listId, "Milk")
        val itemId = waitForItems(listId, 1).single().id

        repository.setPhotoRef(listId, itemId, "users/$uid/items/$itemId/photo-1")
        assertEquals("users/$uid/items/$itemId/photo-1", itemsCollection(listId).document(itemId).get().awaitResult().getString("photoRef"))

        repository.setPhotoRef(listId, itemId, null)
        val raw = itemsCollection(listId).document(itemId).get().awaitResult()
        assertTrue(raw.contains("photoRef"))
        assertNull(raw.get("photoRef"))
    }

    // --- setCompleted: transactional idempotent toggle, retry, concurrency -----------

    @Test
    fun setCompletedTrueThenFalseRoundTripsCompletedItemsExactlyOnce(): Unit = runBlocking {
        val listId = bootstrapList()
        repository.addItem(listId, "Milk")
        val itemId = waitForItems(listId, 1).single().id

        repository.setCompleted(listId, itemId, true)
        assertEquals(1L, listDoc(listId).get().awaitResult().getLong("completedItems"))
        assertEquals(true, itemsCollection(listId).document(itemId).get().awaitResult().getBoolean("isCompleted"))

        repository.setCompleted(listId, itemId, false)
        assertEquals(0L, listDoc(listId).get().awaitResult().getLong("completedItems"))
    }

    @Test
    fun repeatedSetCompletedTrueCallsAreIdempotentAndIncrementCompletedItemsOnlyOnce(): Unit = runBlocking {
        val listId = bootstrapList()
        repository.addItem(listId, "Milk")
        val itemId = waitForItems(listId, 1).single().id

        // A caller retrying an ambiguous (e.g. timed-out but actually-applied) call.
        repository.setCompleted(listId, itemId, true)
        repository.setCompleted(listId, itemId, true)
        repository.setCompleted(listId, itemId, true)

        assertEquals(1L, listDoc(listId).get().awaitResult().getLong("completedItems"))
    }

    @Test
    fun concurrentSetCompletedTrueCallsOnTheSameItemConvergeToASingleIncrement(): Unit = runBlocking {
        val listId = bootstrapList()
        repository.addItem(listId, "Milk")
        val itemId = waitForItems(listId, 1).single().id

        // Real concurrent transactions against the real emulator: proves the SDK's
        // transaction retry-on-contention (read-then-decide, not blind increment)
        // converges to exactly one increment, not six.
        coroutineScope {
            repeat(6) { launch(Dispatchers.IO) { repository.setCompleted(listId, itemId, true) } }
        }

        assertEquals(1L, listDoc(listId).get().awaitResult().getLong("completedItems"))
        assertEquals(true, itemsCollection(listId).document(itemId).get().awaitResult().getBoolean("isCompleted"))
    }

    // --- softDeleteItem / restoreItem: idempotent, scoped to active+completed --------

    @Test
    fun softDeleteItemDecrementsBothCountersWhenTheItemWasActiveAndCompleted(): Unit = runBlocking {
        val listId = bootstrapList()
        repository.addItem(listId, "Milk")
        val itemId = waitForItems(listId, 1).single().id
        repository.setCompleted(listId, itemId, true)

        repository.softDeleteItem(listId, itemId)

        val listRaw = listDoc(listId).get().awaitResult()
        assertEquals(0L, listRaw.getLong("totalItems"))
        assertEquals(0L, listRaw.getLong("completedItems"))
        assertNotNull(itemsCollection(listId).document(itemId).get().awaitResult().getTimestamp("deletedAt"))
    }

    @Test
    fun repeatedSoftDeleteItemCallsAreIdempotentAndDoNotDoubleDecrement(): Unit = runBlocking {
        val listId = bootstrapList()
        repository.addItem(listId, "Milk")
        val itemId = waitForItems(listId, 1).single().id
        repository.setCompleted(listId, itemId, true)

        repository.softDeleteItem(listId, itemId)
        repository.softDeleteItem(listId, itemId) // simulated retry

        val listRaw = listDoc(listId).get().awaitResult()
        assertEquals(0L, listRaw.getLong("totalItems"))
        assertEquals(0L, listRaw.getLong("completedItems"))
    }

    @Test
    fun restoreItemIncrementsBothCountersWhenTheItemWasTombstonedAndCompleted(): Unit = runBlocking {
        val listId = bootstrapList()
        repository.addItem(listId, "Milk")
        val itemId = waitForItems(listId, 1).single().id
        repository.setCompleted(listId, itemId, true)
        repository.softDeleteItem(listId, itemId)

        repository.restoreItem(listId, itemId)

        val listRaw = listDoc(listId).get().awaitResult()
        assertEquals(1L, listRaw.getLong("totalItems"))
        assertEquals(1L, listRaw.getLong("completedItems"))
        val raw = itemsCollection(listId).document(itemId).get().awaitResult()
        assertTrue(raw.contains("deletedAt"))
        assertNull(raw.get("deletedAt"), "restore must clear deletedAt, not merely leave it stale")
    }

    @Test
    fun restoreItemOnAnAlreadyActiveItemIsANoOpForCounters(): Unit = runBlocking {
        val listId = bootstrapList()
        repository.addItem(listId, "Milk")
        val itemId = waitForItems(listId, 1).single().id

        repository.restoreItem(listId, itemId) // never tombstoned

        val listRaw = listDoc(listId).get().awaitResult()
        assertEquals(1L, listRaw.getLong("totalItems"))
        assertEquals(0L, listRaw.getLong("completedItems"))
    }

    // --- deleteItem: genuine hard delete, idempotent w.r.t. a prior soft delete -------

    @Test
    fun deleteItemHardDeletesAndDecrementsCountersWhenTheItemWasActive(): Unit = runBlocking {
        val listId = bootstrapList()
        repository.addItem(listId, "Milk")
        val itemId = waitForItems(listId, 1).single().id
        repository.setCompleted(listId, itemId, true)

        repository.deleteItem(listId, itemId)

        assertFalse(
            itemsCollection(listId).document(itemId).get().awaitResult().exists(),
            "hard delete must remove the document entirely, not tombstone it",
        )
        val listRaw = listDoc(listId).get().awaitResult()
        assertEquals(0L, listRaw.getLong("totalItems"))
        assertEquals(0L, listRaw.getLong("completedItems"))
    }

    @Test
    fun deleteItemAfterAPriorSoftDeleteDoesNotDoubleDecrementCounters(): Unit = runBlocking {
        val listId = bootstrapList()
        repository.addItem(listId, "Milk")
        val itemId = waitForItems(listId, 1).single().id
        repository.setCompleted(listId, itemId, true)
        repository.softDeleteItem(listId, itemId) // already decremented both counters to 0

        repository.deleteItem(listId, itemId) // must not decrement a second time

        assertFalse(itemsCollection(listId).document(itemId).get().awaitResult().exists())
        val listRaw = listDoc(listId).get().awaitResult()
        assertEquals(0L, listRaw.getLong("totalItems"))
        assertEquals(0L, listRaw.getLong("completedItems"))
    }

    @Test
    fun deleteItemOnAMissingItemIsASilentNoOp(): Unit = runBlocking {
        val listId = bootstrapList()

        repository.deleteItem(listId, "never-existed") // must not throw

        val listRaw = listDoc(listId).get().awaitResult()
        assertEquals(0L, listRaw.getLong("totalItems"))
        assertEquals(0L, listRaw.getLong("completedItems"))
    }

    // --- clearCompleted: bulk tombstone + >500-item chunk strategy -------------------

    @Test
    fun clearCompletedTombstonesAllActiveCompletedItemsAndDecrementsBothCountersByTheSameAmount(): Unit = runBlocking {
        val listId = bootstrapList()
        repository.addItem(listId, "Completed 1")
        repository.addItem(listId, "Completed 2")
        repository.addItem(listId, "Completed 3")
        repository.addItem(listId, "Still active")
        val items = waitForItems(listId, 4)
        val completed = items.filter { it.title.startsWith("Completed") }
        completed.forEach { repository.setCompleted(listId, it.id, true) }

        repository.clearCompleted(listId)

        completed.forEach {
            assertNotNull(itemsCollection(listId).document(it.id).get().awaitResult().getTimestamp("deletedAt"))
        }
        val stillActive = items.first { it.title == "Still active" }
        val stillActiveRaw = itemsCollection(listId).document(stillActive.id).get().awaitResult()
        assertTrue(stillActiveRaw.contains("deletedAt"))
        assertNull(stillActiveRaw.get("deletedAt"))
        val listRaw = listDoc(listId).get().awaitResult()
        assertEquals(1L, listRaw.getLong("totalItems"))
        assertEquals(0L, listRaw.getLong("completedItems"))
    }

    /**
     * The chunk-strategy's real (not merely asserted) evidence: with
     * `clearCompletedChunkSize = 5` and 14 completed items, `clearCompleted` cannot
     * possibly satisfy this test in one batch - it must genuinely re-query and commit
     * three separate real [com.google.firebase.firestore.WriteBatch]s (5 + 5 + 4) against
     * the live Firestore emulator. A broken chunk boundary (off-by-one, infinite loop, or
     * an unpaginated single query) would either time out (caught by [withTimeout]) or
     * leave the wrong final counts (caught by the assertions below) - it could not pass
     * silently. The second `clearCompleted` call proves the re-query-driven resumability
     * claim: with nothing left matching "active and completed", it is a genuine no-op.
     */
    @Test
    fun clearCompletedSpansMultipleRealBatchesAndIsIdempotentOnRetry(): Unit = runBlocking {
        val listId = bootstrapList()
        val smallChunkRepository = AndroidFirebaseItemRepository(firestore, CurrentUidProvider { uid }, clearCompletedChunkSize = 5)
        repeat(14) { smallChunkRepository.addItem(listId, "Completed ${it + 1}") }
        val items = withTimeout(TIMEOUT_MS) { smallChunkRepository.observeItems(listId).first { it.size == 14 } }
        items.forEach { smallChunkRepository.setCompleted(listId, it.id, true) }

        withTimeout(TIMEOUT_MS) { smallChunkRepository.clearCompleted(listId) }

        val afterFirstCall = listDoc(listId).get().awaitResult()
        assertEquals(0L, afterFirstCall.getLong("totalItems"))
        assertEquals(0L, afterFirstCall.getLong("completedItems"))
        items.forEach {
            assertNotNull(itemsCollection(listId).document(it.id).get().awaitResult().getTimestamp("deletedAt"))
        }

        withTimeout(TIMEOUT_MS) { smallChunkRepository.clearCompleted(listId) } // idempotent retry: must be a no-op

        val afterRetry = listDoc(listId).get().awaitResult()
        assertEquals(0L, afterRetry.getLong("totalItems"))
        assertEquals(0L, afterRetry.getLong("completedItems"))
    }

    // --- offline: a batched write queued while offline commits once reconnected -------

    @Test
    fun addItemQueuedWhileOfflineCommitsOnceNetworkIsRestored(): Unit = runBlocking {
        val listId = bootstrapList()
        firestore.disableNetwork().awaitResult()
        val deferred = async(Dispatchers.IO) { repository.addItem(listId, "Offline item") }
        try {
            delay(2_000)
            assertTrue(deferred.isActive, "expected the batch commit to remain pending while offline")
        } finally {
            firestore.enableNetwork().awaitResult()
        }

        withTimeout(TIMEOUT_MS) { deferred.await() }

        val listRaw = listDoc(listId).get().awaitResult()
        assertEquals(1L, listRaw.getLong("totalItems"))
    }

    // --- error mapping under real Rules ------------------------------------------------

    @Test
    fun aCrossUserAddItemIsDeniedAndSurfacesAsAForbiddenApplicationErrorNotAnSdkException(): Unit = runBlocking {
        val listId = bootstrapList()
        val someoneElsesUid = "not-$uid"
        val crossUserRepository = AndroidFirebaseItemRepository(firestore, CurrentUidProvider { someoneElsesUid })

        val failure = runCatching { crossUserRepository.addItem(listId, "Not mine") }

        assertTrue(failure.isFailure)
        val exception = assertIs<ListRepositoryException>(failure.exceptionOrNull())
        assertEquals(RepositoryErrorCode.FORBIDDEN, exception.error.code)
    }

    // --- listener-cancellation obligation ----------------------------------------------

    @Test
    fun cancellingTheItemsCollectorReleasesTheRealFirestoreListener(): Unit = runBlocking {
        val listId = bootstrapList()
        val seen = mutableListOf<List<FluxItem>>()
        val collector = repository.observeItems(listId).onEach { seen += it }.launchIn(scope)
        withTimeout(TIMEOUT_MS) { while (seen.isEmpty()) yield() }
        val beforeCancellation = seen.size

        collector.cancelAndJoin()

        val secondRepository = AndroidFirebaseItemRepository(firestore, CurrentUidProvider { uid })
        secondRepository.addItem(listId, "Should not be observed")
        delay(2_000)

        assertEquals(
            beforeCancellation,
            seen.size,
            "a released listener must not keep pushing snapshot updates after cancellation",
        )
    }

    // --- helpers -------------------------------------------------------------------

    private suspend fun waitForItems(listId: String, count: Int): List<FluxItem> =
        withTimeout(TIMEOUT_MS) { repository.observeItems(listId).first { it.size == count } }

    private fun listDoc(id: String) = firestore.collection("users").document(uid).collection("lists").document(id)

    private fun itemsCollection(listId: String) = listDoc(listId).collection("items")

    /**
     * Bootstraps a bare list document with the exact fields
     * [AndroidFirebaseItemRepository]'s counter transactions read/write. Deliberately not
     * routed through [com.fluxit.firebase.list.AndroidFirebaseListRepository] so this
     * file has no FB-202 dependency; that repository's own creation correctness is
     * already proven by its own suite.
     */
    private suspend fun bootstrapList(): String {
        val id = UUID.randomUUID().toString()
        listDoc(id).set(
            mapOf(
                "name" to "Groceries",
                "icon" to "CART",
                "color" to "PRIMARY_BLUE",
                "createdAt" to Timestamp.now(),
                "updatedAt" to Timestamp.now(),
                "deletedAt" to null,
                "totalItems" to 0L,
                "completedItems" to 0L,
                "schemaVersion" to 1L,
            )
        ).awaitResult()
        return id
    }

    private fun uniqueEmail(): String = "fb204-${UUID.randomUUID()}@example.test"

    private fun emulatorHost(): String = when (FirebaseEmulatorConfig.HOST) {
        "127.0.0.1", "localhost" -> ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS
        else -> FirebaseEmulatorConfig.HOST
    }

    private companion object {
        const val APP_NAME = "fb204-instrumented-test"
        /** Throwaway passphrase for emulator-only accounts; not a credential. */
        const val PASSWORD = "fb204-emulator-only"
        const val TIMEOUT_MS = 30_000L
        const val ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS = "10.0.2.2"

        /** `useEmulator` may only be called once per SDK instance. */
        var authEmulatorConfigured = false
        var firestoreEmulatorConfigured = false
    }
}

/**
 * Local `Task.await()`, mirroring `FirestoreListEmulatorIntegrationTest`'s identically
 * named/shaped helper (this module has no `kotlinx-coroutines-play-services` dependency).
 */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val exception = task.exception
        when {
            exception != null -> continuation.resumeWithException(exception)
            task.isCanceled -> continuation.cancel(CancellationException("Firebase task cancelled"))
            else -> continuation.resume(task.result)
        }
    }
}
