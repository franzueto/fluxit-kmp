package com.fluxit.firebase.list

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxit.config.FirebaseEmulatorConfig
import com.fluxit.domain.FluxList
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
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
import kotlin.test.assertNotNull
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
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android cross-client checks for [AndroidFirebaseListRepository]: conflict
 * (field-level last-write-wins per the field-level last-write-wins policy), malformed-document (no `valueOf`
 * crash), and reconnect/pending-write, against the same real Firestore emulator
 * [FirestoreListEmulatorIntegrationTest] exercises.
 *
 * "Cross-client" here means two genuinely independent Firebase SDK connections - two
 * separately named [FirebaseApp] instances, each with its own [FirebaseAuth]/
 * [FirebaseFirestore] (and therefore its own local cache and network on/off state) -
 * signed in as the **same** uid. This mirrors the app's actual multi-device model
 * (the field-level last-write-wins policy is built for "a single user editing their own lists across their own
 * devices"), not a multi-user scenario (already covered by the cross-user-denial tests
 * in the Rules suite).
 *
 * Prerequisites/run command: identical to `FirestoreListEmulatorIntegrationTest`'s KDoc.
 */
@RunWith(AndroidJUnit4::class)
class CrossClientListEmulatorIntegrationTest {

    private lateinit var authA: FirebaseAuth
    private lateinit var authB: FirebaseAuth
    private lateinit var firestoreA: FirebaseFirestore
    private lateinit var firestoreB: FirebaseFirestore
    private lateinit var uid: String
    private lateinit var clientA: AndroidFirebaseListRepository
    private lateinit var clientB: AndroidFirebaseListRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun connectTwoIndependentClientsAsTheSameUser(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (builtAuthA, builtFirestoreA) = buildClientApp(context, "xclient-list-client-a")
        val (builtAuthB, builtFirestoreB) = buildClientApp(context, "xclient-list-client-b")
        authA = builtAuthA
        authB = builtAuthB
        firestoreA = builtFirestoreA
        firestoreB = builtFirestoreB

        val email = uniqueEmail()
        authA.createUserWithEmailAndPassword(email, PASSWORD).awaitResult()
        uid = requireNotNull(authA.currentUser?.uid) { "client A sign-up did not resolve a uid" }
        authB.signInWithEmailAndPassword(email, PASSWORD).awaitResult()
        check(authB.currentUser?.uid == uid) { "client B resolved a different uid than client A" }

        clientA = AndroidFirebaseListRepository(firestoreA, CurrentUidProvider { uid })
        clientB = AndroidFirebaseListRepository(firestoreB, CurrentUidProvider { uid })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun signOutBothClientsAndRelease() {
        authA.signOut()
        authB.signOut()
        scope.cancel()
    }

    // --- conflict: field-level last-write-wins -------------------------------

    /**
     * the field-level last-write-wins policy headline promise: concurrent edits to *different* fields on the same
     * document merge automatically. `updateList` patches `name`/`icon`/`color` together
     * as one field-scoped call, and `softDeleteList` patches only `deletedAt` - these
     * two calls touch entirely disjoint field sets, so racing them proves the merge
     * without either clobbering the other.
     */
    @Test
    fun conflictDifferentFieldEditsFromTwoClientsMergeViaFieldLevelPatches(): Unit = runBlocking {
        val id = clientA.createList("Groceries", ListIcon.CART, ListColor.PRIMARY_BLUE)

        coroutineScope {
            launch { clientA.updateList(id, "Weekly Groceries", ListIcon.FOOD, ListColor.ROSE) }
            launch { clientB.softDeleteList(id) }
        }

        val raw = listDocOn(firestoreA, id).get().awaitResult()
        assertEquals("Weekly Groceries", raw.getString("name"), "client A's field-scoped patch must land")
        assertEquals("FOOD", raw.getString("icon"))
        assertEquals("ROSE", raw.getString("color"))
        assertNotNull(raw.getTimestamp("deletedAt"), "client B's disjoint field-scoped patch must also land, not be clobbered by A's set")
    }

    /**
     * A genuine same-field collision: both clients patch `name` (via `updateList`,
     * which also touches `icon`/`color`, but this test holds those constant across
     * both calls so only `name` is a moving target). Issued in a controlled, causally
     * ordered sequence (B's call only starts after A's has committed) so the "later
     * writer wins" outcome is deterministic rather than a coin flip - and, per
     * the field-level last-write-wins policy, silent: neither call may throw or surface a conflict error.
     */
    @Test
    fun conflictSameFieldEditFromTwoClientsResolvesToTheLaterWriterSilently(): Unit = runBlocking {
        val id = clientA.createList("Groceries", ListIcon.CART, ListColor.PRIMARY_BLUE)

        clientA.updateList(id, "Client A's name", ListIcon.CART, ListColor.PRIMARY_BLUE)
        clientB.updateList(id, "Client B's name", ListIcon.CART, ListColor.PRIMARY_BLUE) // strictly later

        val raw = listDocOn(firestoreA, id).get().awaitResult()
        assertEquals(
            "Client B's name",
            raw.getString("name"),
            "the later writer must silently win a same-field collision, per the field-level last-write-wins policy",
        )
    }

    // --- reconnect: an already-registered listener re-syncs after network loss --------

    /**
     * Client B's listener is registered *before* it goes offline and is never torn
     * down/recreated - only [FirebaseFirestore.disableNetwork]/[FirebaseFirestore.enableNetwork]
     * toggle. This proves listener re-sync specifically, as distinct from a fresh
     * listener simply reading current state.
     */
    @Test
    fun reconnectClientBsExistingListenerResyncsAfterNetworkLossWhileClientAKeepsWriting(): Unit = runBlocking {
        val id = clientA.createList("Groceries", ListIcon.CART, ListColor.PRIMARY_BLUE)
        val seen = mutableListOf<FluxList?>()
        val collector = clientB.observeList(id).onEach { seen += it }.launchIn(scope)
        withTimeout(TIMEOUT_MS) { while (seen.isEmpty()) yield() }

        firestoreB.disableNetwork().awaitResult()
        try {
            clientA.updateList(id, "Weekly Groceries", ListIcon.FOOD, ListColor.ROSE)
            delay(1_500) // give a (bugged) live listener a fair chance to leak this through anyway
        } finally {
            firestoreB.enableNetwork().awaitResult()
        }

        withTimeout(TIMEOUT_MS) { while (seen.lastOrNull()?.name != "Weekly Groceries") yield() }
        collector.cancel()
        assertEquals("FOOD", seen.last()?.icon?.name)
    }

    // --- pending-write: an offline client's queued write is invisible until reconnect -

    @Test
    fun pendingWriteOfflineClientAsCreateIsInvisibleToClientBUntilReconnect(): Unit = runBlocking {
        firestoreA.disableNetwork().awaitResult()
        val deferred = async(Dispatchers.IO) {
            clientA.createList("Offline List", ListIcon.CART, ListColor.PRIMARY_BLUE)
        }
        try {
            delay(1_500)
            assertTrue(deferred.isActive, "expected the create to remain queued locally while client A is offline")

            val whileOffline = withTimeout(TIMEOUT_MS) { clientB.observeListSummaries().first() }
            assertTrue(
                whileOffline.none { it.list.name == "Offline List" },
                "client B must not see client A's not-yet-committed offline write",
            )
        } finally {
            firestoreA.enableNetwork().awaitResult()
        }

        val id = withTimeout(TIMEOUT_MS) { deferred.await() }
        val afterReconnect = withTimeout(TIMEOUT_MS) {
            clientB.observeListSummaries().first { summaries -> summaries.any { it.list.id == id } }
        }
        assertTrue(afterReconnect.any { it.list.id == id && it.list.name == "Offline List" })
    }

    // --- malformed-document: unknown/missing/invalid values never crash a reader ------

    @Test
    fun malformedDocumentMissingRequiredFieldIsExcludedFromObserveListSummariesWithoutCrashing(): Unit = runBlocking {
        val goodId = clientA.createList("Good List", ListIcon.CART, ListColor.PRIMARY_BLUE)
        val badId = writeRawList(omit = setOf("name")) // `name` required, genuinely absent

        val summaries = withTimeout(TIMEOUT_MS) { clientB.observeListSummaries().first { it.isNotEmpty() } }

        assertTrue(summaries.any { it.list.id == goodId }, "a sibling valid document must still surface")
        assertTrue(summaries.none { it.list.id == badId }, "a malformed document must be silently excluded, not crash the listener")
    }

    /** The plan's Phase 2 "no `valueOf` crash" concern, exercised for real. */
    @Test
    fun malformedDocumentUnknownEnumValueFallsBackToTheDefaultInsteadOfCrashing(): Unit = runBlocking {
        val badId = writeRawList(mapOf("icon" to "NOT_A_REAL_ICON_VALUE"))

        val list = withTimeout(TIMEOUT_MS) { clientB.observeList(badId).first { it != null } }

        assertNotNull(list)
        assertEquals(ListIcon.CART, list.icon, "an unrecognized enum literal must fall back, never crash via valueOf")
    }

    @Test
    fun malformedDocumentCompletedGreaterThanTotalIsExcludedAsAnInvalidValue(): Unit = runBlocking {
        val badId = writeRawList(mapOf("totalItems" to 1L, "completedItems" to 5L))

        val nullOrAbsent = withTimeout(TIMEOUT_MS) { clientB.observeList(badId).first() }

        assertNull(nullOrAbsent, "completedItems > totalItems is an invalid document and must not surface")
    }

    // --- helpers -------------------------------------------------------------------

    private fun listDocOn(firestore: FirebaseFirestore, id: String): DocumentReference =
        firestore.collection("users").document(uid).collection("lists").document(id)

    /**
     * Admin-injects a historical malformed list into the fixed demo emulator (not a client write)
     * with a valid baseline schema, [overrides] applied on top - so each malformed test
     * only has to name the one field it wants broken.
     */
    private suspend fun writeRawList(overrides: Map<String, Any?> = emptyMap(), omit: Set<String> = emptySet()): String {
        val id = UUID.randomUUID().toString()
        val baseline = mapOf(
            "name" to "Malformed",
            "icon" to "CART",
            "color" to "PRIMARY_BLUE",
            "createdAt" to Timestamp.now(),
            "updatedAt" to Timestamp.now(),
            "deletedAt" to null,
            "totalItems" to 0L,
            "completedItems" to 0L,
            "schemaVersion" to 1L,
        )
        val fields = (baseline + overrides) - omit
        com.fluxit.firebase.EmulatorMalformedFixture.put(
            firestoreA.app.options.projectId!!, listDocOn(firestoreA, id).path, fields,
        )
        return id
    }

    private suspend fun buildClientApp(context: Context, appName: String): Pair<FirebaseAuth, FirebaseFirestore> {
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == appName }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    // Throwaway values: both emulators accept any key/app id, and a
                    // `demo-` project id can never resolve to a real Firebase project.
                    .setApiKey("xclient-instrumented-test-key")
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

    private fun uniqueEmail(): String = "xclient-list-${UUID.randomUUID()}@example.test"

    private fun emulatorHost(): String = when (FirebaseEmulatorConfig.HOST) {
        "127.0.0.1", "localhost" -> ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS
        else -> FirebaseEmulatorConfig.HOST
    }

    private companion object {
        /** Throwaway passphrase for emulator-only accounts; not a credential. */
        const val PASSWORD = "xclient-emulator-only"
        const val TIMEOUT_MS = 20_000L
        const val ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS = "10.0.2.2"

        /** `useEmulator` may only be called once per SDK instance; apps are reused by name across test methods. */
        val emulatorConfiguredAppNames = mutableSetOf<String>()
    }
}

/**
 * Local `Task.await()`, mirroring `FirestoreListEmulatorIntegrationTest`'s/
 * `FirestoreItemEmulatorIntegrationTest`'s identically named/shaped helper (this module
 * has no `kotlinx-coroutines-play-services` dependency).
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
