package com.fluxit.firebase.item

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxit.config.FirebaseEmulatorConfig
import com.fluxit.domain.FluxItem
import com.fluxit.firebase.list.CurrentUidProvider
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FB-206 Android cross-client checks for [AndroidFirebaseItemRepository]: counter
 * consistency, conflict (field-level LWW plus the counter-exempt-from-LWW rule),
 * malformed-document, and pending-write/reconnect, against the same real Firestore
 * emulator [com.fluxit.firebase.list.FirestoreListEmulatorIntegrationTest] (FB-202)
 * exercises. Also exercises `FB-204-NB1`'s documented `clearCompleted` chunk-window
 * caveat explicitly, per this task's brief.
 *
 * "Cross-client" here is two genuinely independent Firebase SDK connections (two
 * separately named [FirebaseApp] instances, each with its own [FirebaseAuth]/
 * [FirebaseFirestore]) signed in as the **same** uid - the app's real multi-device
 * model, not a multi-user one (already covered elsewhere). See
 * `com.fluxit.firebase.list.CrossClientListEmulatorIntegrationTest`'s KDoc for the same
 * rationale; this file intentionally duplicates rather than shares that harness, for
 * the same self-contained-artifact reason `FirestoreItemEmulatorIntegrationTest`
 * documents against `FirestoreListEmulatorIntegrationTest`.
 *
 * Prerequisites/run command: identical to `FirestoreItemEmulatorIntegrationTest`'s KDoc.
 */
@RunWith(AndroidJUnit4::class)
class CrossClientItemEmulatorIntegrationTest {

    private lateinit var authA: FirebaseAuth
    private lateinit var authB: FirebaseAuth
    private lateinit var firestoreA: FirebaseFirestore
    private lateinit var firestoreB: FirebaseFirestore
    private lateinit var uid: String
    private lateinit var clientA: AndroidFirebaseItemRepository
    private lateinit var clientB: AndroidFirebaseItemRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun connectTwoIndependentClientsAsTheSameUser(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (builtAuthA, builtFirestoreA) = buildClientApp(context, "fb206-item-client-a")
        val (builtAuthB, builtFirestoreB) = buildClientApp(context, "fb206-item-client-b")
        authA = builtAuthA
        authB = builtAuthB
        firestoreA = builtFirestoreA
        firestoreB = builtFirestoreB

        val email = uniqueEmail()
        authA.createUserWithEmailAndPassword(email, PASSWORD).awaitResult()
        uid = requireNotNull(authA.currentUser?.uid) { "client A sign-up did not resolve a uid" }
        authB.signInWithEmailAndPassword(email, PASSWORD).awaitResult()
        check(authB.currentUser?.uid == uid) { "client B resolved a different uid than client A" }

        clientA = AndroidFirebaseItemRepository(firestoreA, CurrentUidProvider { uid })
        clientB = AndroidFirebaseItemRepository(firestoreB, CurrentUidProvider { uid })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun signOutBothClientsAndRelease() {
        authA.signOut()
        authB.signOut()
        scope.cancel()
    }

    // --- cross-client counter consistency ----------------------------------------------

    @Test
    fun crossClientCounterConsistencyConcurrentSetCompletedFromTwoIndependentConnectionsConvergesToOneIncrement(): Unit = runBlocking {
        val listId = bootstrapList()
        clientA.addItem(listId, "Milk")
        val itemId = waitForItems(clientA, listId, 1).single().id

        // Unlike FB-204's own single-instance concurrency test (six coroutines racing on
        // one Firestore SDK connection), this races two *independent* SDK connections -
        // proving the SDK's transaction-retry-on-contention converges across connections,
        // not merely across coroutines sharing one.
        coroutineScope {
            launch { clientA.setCompleted(listId, itemId, true) }
            launch { clientB.setCompleted(listId, itemId, true) }
        }

        assertEquals(1L, listDocOn(firestoreA, listId).get().awaitResult().getLong("completedItems"))
    }

    @Test
    fun crossClientCounterConsistencyConcurrentAddItemFromBothClientsBothIncrementTotalItems(): Unit = runBlocking {
        val listId = bootstrapList()

        coroutineScope {
            launch { clientA.addItem(listId, "From A") }
            launch { clientB.addItem(listId, "From B") }
        }

        val items = waitForItems(clientA, listId, 2)
        assertEquals(setOf("From A", "From B"), items.map { it.title }.toSet())
        assertEquals(2L, listDocOn(firestoreA, listId).get().awaitResult().getLong("totalItems"))
    }

    // --- conflict: DEC-003d field-level LWW, counters explicitly exempt ---------------

    /** `updateItem` (title+description) and `setPhotoPath` (photoRef) are disjoint field-scoped patches. */
    @Test
    fun conflictDifferentFieldEditsFromTwoClientsMergeTitleAndPhotoRef(): Unit = runBlocking {
        val listId = bootstrapList()
        clientA.addItem(listId, "Milk")
        val itemId = waitForItems(clientA, listId, 1).single().id

        coroutineScope {
            launch { clientA.updateItem(listId, itemId, "Whole Milk", "2%") }
            launch { clientB.setPhotoPath(listId, itemId, "users/$uid/items/$itemId/photo-1") }
        }

        val raw = itemsCollectionOn(firestoreA, listId).document(itemId).get().awaitResult()
        assertEquals("Whole Milk", raw.getString("title"), "client A's field-scoped patch must land")
        assertEquals("2%", raw.getString("description"))
        assertEquals(
            "users/$uid/items/$itemId/photo-1",
            raw.getString("photoRef"),
            "client B's disjoint field-scoped patch must also land, not be clobbered by A's",
        )
    }

    @Test
    fun conflictSameFieldEditFromTwoClientsResolvesToTheLaterWriterSilently(): Unit = runBlocking {
        val listId = bootstrapList()
        clientA.addItem(listId, "Milk")
        val itemId = waitForItems(clientA, listId, 1).single().id

        clientA.updateItem(listId, itemId, "Client A's title", null)
        clientB.updateItem(listId, itemId, "Client B's title", null) // strictly later

        val raw = itemsCollectionOn(firestoreA, listId).document(itemId).get().awaitResult()
        assertEquals(
            "Client B's title",
            raw.getString("title"),
            "the later writer must silently win a same-field collision, per DEC-003d",
        )
    }

    /**
     * Counters are explicitly exempt from LWW (`DEC-003d`): they never "collide" the
     * way a text field does, because increments commute. Two clients concurrently
     * toggling completion on two *different* items must both be reflected exactly,
     * never one clobbering the other the way a naive `set()` of a stale counter value
     * would.
     */
    @Test
    fun conflictCountersAreExemptFromLwwAndBothConcurrentIncrementsCommute(): Unit = runBlocking {
        val listId = bootstrapList()
        clientA.addItem(listId, "Item 1")
        clientA.addItem(listId, "Item 2")
        val items = waitForItems(clientA, listId, 2)

        coroutineScope {
            launch { clientA.setCompleted(listId, items[0].id, true) }
            launch { clientB.setCompleted(listId, items[1].id, true) }
        }

        assertEquals(2L, listDocOn(firestoreA, listId).get().awaitResult().getLong("completedItems"))
    }

    // --- FB-204-NB1: clearCompleted's chunk sweep is per-chunk-atomic, not whole-sweep -

    /**
     * Exercises the exact window `FB-204-NB1` documents: `clearCompleted`'s chunked
     * sweep commits one real [com.google.firebase.firestore.WriteBatch] per chunk, so an
     * item concurrently un-completed mid-sweep can still be counted/tombstoned within
     * whichever chunk had already read it. With `clearCompletedChunkSize = 1` there is a
     * real network round-trip (and therefore a real suspension point) between each
     * chunk's commit and the next chunk's query, giving client B's racing
     * `setCompleted(false)` call a genuine window to land either before or after any
     * given item's chunk is processed.
     *
     * This test does not - and per the task's own instruction, must not - assume false
     * whole-operation atomicity by asserting a single fixed winner. It asserts the
     * *documented* invariant instead: whichever way the race lands, the final state for
     * the raced item must be one of exactly two *consistent* outcomes, never a third,
     * corrupted one (e.g. tombstoned while still counted, or vice versa).
     */
    @Test
    fun conflictClearCompletedMidSweepRaceLeavesOnlyTheTwoDocumentedConsistentOutcomes(): Unit = runBlocking {
        val listId = bootstrapList()
        val smallChunkClientA = AndroidFirebaseItemRepository(firestoreA, CurrentUidProvider { uid }, clearCompletedChunkSize = 1)
        repeat(3) { smallChunkClientA.addItem(listId, "Completed ${it + 1}") }
        val items = waitForItems(smallChunkClientA, listId, 3)
        items.forEach { smallChunkClientA.setCompleted(listId, it.id, true) }
        val racedItemId = items.last().id

        coroutineScope {
            val sweep = launch { smallChunkClientA.clearCompleted(listId) }
            launch {
                delay(150) // best-effort: land inside the multi-chunk window, not before it starts
                clientB.setCompleted(listId, racedItemId, false)
            }
            sweep.join()
        }

        val racedRaw = itemsCollectionOn(firestoreA, listId).document(racedItemId).get().awaitResult()
        val wasTombstoned = racedRaw.exists() && racedRaw.getTimestamp("deletedAt") != null
        val listRaw = listDocOn(firestoreA, listId).get().awaitResult()
        val totalItems = listRaw.getLong("totalItems") ?: -1L
        val completedItems = listRaw.getLong("completedItems") ?: -1L

        if (wasTombstoned) {
            // Outcome A: clearCompleted's chunk already read the item as completed
            // before client B's un-complete landed - it was swept up anyway, exactly
            // the documented FB-204-NB1 window.
            assertTrue(totalItems in 0..1, "tombstoned outcome: totalItems=$totalItems")
        } else {
            // Outcome B: client B's un-complete won the race - the item survives,
            // active and no longer completed, correctly excluded from the sweep by a
            // later chunk's re-query.
            assertEquals(false, racedRaw.getBoolean("isCompleted"), "surviving outcome must reflect the un-complete")
            assertTrue(totalItems in 1..2, "surviving outcome: totalItems=$totalItems")
        }
        // Never a third, corrupted outcome regardless of which branch fired.
        assertTrue(completedItems == 0L, "no item should remain marked completed after this test's sequence, got $completedItems")
    }

    // --- pending-write: an offline client's queued write is invisible until reconnect -

    @Test
    fun pendingWriteOfflineClientAsAddItemIsInvisibleToClientBUntilReconnect(): Unit = runBlocking {
        val listId = bootstrapList()
        firestoreA.disableNetwork().awaitResult()
        val deferred = async(Dispatchers.IO) { clientA.addItem(listId, "Offline item") }
        try {
            delay(1_500)
            assertTrue(deferred.isActive, "expected the batch commit to remain queued while client A is offline")

            val whileOffline = withTimeout(TIMEOUT_MS) { clientB.observeItems(listId).first() }
            assertTrue(
                whileOffline.none { it.title == "Offline item" },
                "client B must not see client A's not-yet-committed offline write",
            )
        } finally {
            firestoreA.enableNetwork().awaitResult()
        }

        withTimeout(TIMEOUT_MS) { deferred.await() }
        val afterReconnect = withTimeout(TIMEOUT_MS) {
            clientB.observeItems(listId).first { it.any { item -> item.title == "Offline item" } }
        }
        assertTrue(afterReconnect.any { it.title == "Offline item" })
    }

    // --- reconnect: an already-registered listener re-syncs after network loss --------

    @Test
    fun reconnectClientBsExistingListenerResyncsAfterNetworkLossWhileClientAKeepsWriting(): Unit = runBlocking {
        val listId = bootstrapList()
        val seen = mutableListOf<List<FluxItem>>()
        val collector = clientB.observeItems(listId).onEach { seen += it }.launchIn(scope)
        withTimeout(TIMEOUT_MS) { while (seen.isEmpty()) yield() }

        firestoreB.disableNetwork().awaitResult()
        try {
            clientA.addItem(listId, "Added while B offline")
            delay(1_500)
        } finally {
            firestoreB.enableNetwork().awaitResult()
        }

        withTimeout(TIMEOUT_MS) { while (seen.lastOrNull()?.none { it.title == "Added while B offline" } != false) yield() }
        collector.cancel()
        assertTrue(seen.last().any { it.title == "Added while B offline" })
    }

    // --- malformed-document: unknown/missing/invalid values never crash a reader ------

    @Test
    fun malformedDocumentWrongTypeIsCompletedIsExcludedFromObserveItemsWithoutCrashing(): Unit = runBlocking {
        val listId = bootstrapList()
        clientA.addItem(listId, "Good item")
        val badId = writeRawItem(listId, overrides = mapOf("isCompleted" to "not-a-boolean"))

        val items = withTimeout(TIMEOUT_MS) { clientB.observeItems(listId).first { it.isNotEmpty() } }

        assertTrue(items.any { it.title == "Good item" }, "a sibling valid document must still surface")
        assertTrue(items.none { it.id == badId }, "a malformed document must be silently excluded, not crash the listener")
    }

    @Test
    fun malformedDocumentIsExcludedFromObserveItemAsNullInsteadOfCrashing(): Unit = runBlocking {
        val listId = bootstrapList()
        val badId = writeRawItem(listId, overrides = mapOf("title" to null))

        val result = withTimeoutOrNull(5_000) { clientB.observeItem(listId, badId).first { true } }

        assertNull(result, "observeItem must resolve a malformed document to null, exactly like a missing/tombstoned one, never throw")
    }

    @Test
    fun malformedDocumentListIdMismatchIsExcludedAsAPathMismatch(): Unit = runBlocking {
        val listId = bootstrapList()
        val otherListId = bootstrapList()
        val badId = writeRawItem(listId, overrides = mapOf("listId" to otherListId))

        val items = withTimeout(TIMEOUT_MS) { clientB.observeItems(listId).first() }

        assertTrue(items.none { it.id == badId }, "an item whose listId field disagrees with its actual path must be excluded, not crash")
    }

    // --- helpers -------------------------------------------------------------------

    private suspend fun waitForItems(repository: AndroidFirebaseItemRepository, listId: String, count: Int): List<FluxItem> =
        withTimeout(TIMEOUT_MS) { repository.observeItems(listId).first { it.size == count } }

    private fun listDocOn(firestore: FirebaseFirestore, id: String): DocumentReference =
        firestore.collection("users").document(uid).collection("lists").document(id)

    private fun itemsCollectionOn(firestore: FirebaseFirestore, listId: String) = listDocOn(firestore, listId).collection("items")

    /**
     * Bootstraps a bare list document with the exact fields
     * [AndroidFirebaseItemRepository]'s counter transactions read/write. Deliberately
     * not routed through [com.fluxit.firebase.list.AndroidFirebaseListRepository], same
     * rationale `FirestoreItemEmulatorIntegrationTest`'s identically named helper gives.
     */
    private suspend fun bootstrapList(): String {
        val id = UUID.randomUUID().toString()
        listDocOn(firestoreA, id).set(
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

    /**
     * Writes a raw item document directly (bypassing [AndroidFirebaseItemRepository])
     * with a valid baseline schema, [overrides] applied on top.
     */
    private suspend fun writeRawItem(listId: String, overrides: Map<String, Any?>): String {
        val id = UUID.randomUUID().toString()
        val baseline = mapOf(
            "listId" to listId,
            "title" to "Malformed",
            "description" to null,
            "isCompleted" to false,
            "photoRef" to null,
            "createdAt" to Timestamp.now(),
            "updatedAt" to Timestamp.now(),
            "deletedAt" to null,
            "schemaVersion" to 1L,
        )
        itemsCollectionOn(firestoreA, listId).document(id).set(baseline + overrides).awaitResult()
        return id
    }

    private suspend fun buildClientApp(context: Context, appName: String): Pair<FirebaseAuth, FirebaseFirestore> {
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == appName }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    // Throwaway values: both emulators accept any key/app id, and a
                    // `demo-` project id can never resolve to a real Firebase project.
                    .setApiKey("fb206-instrumented-test-key")
                    .setApplicationId("1:0:android:$appName")
                    .setProjectId("demo-fluxit")
                    .build(),
                appName,
            )
        val auth = FirebaseAuth.getInstance(app).apply {
            if (appName !in emulatorConfiguredAppNames) {
                useEmulator(emulatorHost(), FirebaseEmulatorConfig.AUTH_PORT)
            }
            signOut()
        }
        val firestore = FirebaseFirestore.getInstance(app).apply {
            if (appName !in emulatorConfiguredAppNames) {
                useEmulator(emulatorHost(), FirebaseEmulatorConfig.FIRESTORE_PORT)
            }
        }
        emulatorConfiguredAppNames += appName
        // Defensive: a named FirebaseApp is reused across test methods (looked up by
        // name above), so an earlier test's offline probe could otherwise leave this
        // instance's network disabled for a later, unrelated test.
        firestore.enableNetwork().awaitResult()
        return auth to firestore
    }

    private fun uniqueEmail(): String = "fb206-item-${UUID.randomUUID()}@example.test"

    private fun emulatorHost(): String = when (FirebaseEmulatorConfig.HOST) {
        "127.0.0.1", "localhost" -> ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS
        else -> FirebaseEmulatorConfig.HOST
    }

    private companion object {
        /** Throwaway passphrase for emulator-only accounts; not a credential. */
        const val PASSWORD = "fb206-emulator-only"
        const val TIMEOUT_MS = 30_000L
        const val ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS = "10.0.2.2"

        /** `useEmulator` may only be called once per SDK instance; apps are reused by name across test methods. */
        val emulatorConfiguredAppNames = mutableSetOf<String>()
    }
}

/**
 * Local `Task.await()`, mirroring `FirestoreItemEmulatorIntegrationTest`'s identically
 * named/shaped helper (this module has no `kotlinx-coroutines-play-services`
 * dependency).
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
