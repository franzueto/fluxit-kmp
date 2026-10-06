package com.fluxit.firebase.list

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxit.config.FirebaseEmulatorConfig
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.domain.FluxListSummary
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FB-202 Android integration checks: the real Firebase Android Firestore SDK, driven
 * through [AndroidFirebaseListRepository], against a real Firestore emulator - the
 * first time the Firestore emulator has been exercised by either platform in this
 * migration.
 *
 * Deliberately *not* against the live development project: every document this suite
 * writes lives only in the emulator's in-memory store and disappears with the emulator
 * process. Authentication is real too (against the Auth emulator, via a dedicated
 * secondary [FirebaseApp] built from throwaway options), because `firestore.rules` is
 * owner-only and needs a real ID token carrying a real `uid` to authorize anything -
 * without it every operation below would be denied, not merely unauthenticated.
 *
 * Prerequisites, because this test cannot provision them itself:
 *
 * 1. an Android emulator/device with the host reachable at `10.0.2.2` (a stock AVD is);
 * 2. the Auth emulator running on the host on port
 *    [FirebaseEmulatorConfig.AUTH_PORT] (see `FirebaseAuthEmulatorIntegrationTest`'s
 *    KDoc for the exact command);
 * 3. the Firestore emulator running on the host on port
 *    [FirebaseEmulatorConfig.FIRESTORE_PORT], with `firestore.rules` loaded (from the
 *    repository root: `firebase/node_modules/.bin/firebase emulators:start
 *    --only firestore --project demo-fluxit`, or `--only auth,firestore` to cover both
 *    prerequisites from one process).
 *
 * Run with: `./gradlew :composeApp:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class FirestoreListEmulatorIntegrationTest {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var uid: String
    private lateinit var repository: AndroidFirebaseListRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun connectToTheEmulatorsAndSignIn(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = FirebaseApp.getApps(context)
            .firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    // Throwaway values: both emulators accept any key/app id, and a
                    // `demo-` project id can never resolve to a real Firebase project.
                    .setApiKey("fb202-instrumented-test-key")
                    .setApplicationId("1:0:android:fb202")
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
        }
        auth.createUserWithEmailAndPassword(uniqueEmail(), PASSWORD).awaitResult()
        uid = requireNotNull(auth.currentUser?.uid) { "sign-up did not resolve a uid" }
        repository = AndroidFirebaseListRepository(firestore, CurrentUidProvider { uid })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun signOutAndRelease() {
        auth.signOut()
        scope.cancel()
    }

    // --- create / read / ordering ---------------------------------------------------

    @Test
    fun createListWritesTheFullInitialSchema(): Unit = runBlocking {
        val id = repository.createList("Groceries", ListIcon.CART, ListColor.PRIMARY_BLUE)

        val raw = listDoc(id).get().awaitResult()
        assertTrue(raw.exists())
        assertEquals("Groceries", raw.getString("name"))
        assertEquals("CART", raw.getString("icon"))
        assertEquals("PRIMARY_BLUE", raw.getString("color"))
        assertNotNull(raw.getTimestamp("createdAt"))
        assertNotNull(raw.getTimestamp("updatedAt"))
        assertTrue(raw.contains("deletedAt"))
        assertNull(raw.get("deletedAt"))
        assertEquals(0L, raw.getLong("totalItems"))
        assertEquals(0L, raw.getLong("completedItems"))
        assertEquals(1L, raw.getLong("schemaVersion"))
    }

    @Test
    fun observeListSummariesOrdersByCreatedAtThenDocumentIdAndFiltersTombstones(): Unit = runBlocking {
        val firstId = repository.createList("A", ListIcon.CART, ListColor.PRIMARY_BLUE)
        val secondId = repository.createList("B", ListIcon.WORK, ListColor.ORANGE)
        val thirdId = repository.createList("C", ListIcon.HOME, ListColor.EMERALD)

        val summariesBeforeDelete = withTimeout(TIMEOUT_MS) {
            repository.observeListSummaries().first { it.size == 3 }
        }
        assertEquals(listOf(firstId, secondId, thirdId), summariesBeforeDelete.map { it.list.id })
        assertTrue(
            summariesBeforeDelete.zipWithNext().all { (a, b) -> a.list.createdAt <= b.list.createdAt },
            "expected non-decreasing createdAt order, got ${summariesBeforeDelete.map { it.list.createdAt }}",
        )

        repository.softDeleteList(secondId)

        val summariesAfterDelete = withTimeout(TIMEOUT_MS) {
            repository.observeListSummaries().first { it.none { s -> s.list.id == secondId } }
        }
        assertEquals(listOf(firstId, thirdId), summariesAfterDelete.map { it.list.id })
        // The tombstoned document must still exist server-side - this is a filtered
        // live view, not a delete.
        val rawTombstone = listDoc(secondId).get().awaitResult()
        assertTrue(rawTombstone.exists())
        assertNotNull(rawTombstone.getTimestamp("deletedAt"))
    }

    @Test
    fun observeListReturnsNullForATombstonedList(): Unit = runBlocking {
        val id = repository.createList("Groceries", ListIcon.CART, ListColor.PRIMARY_BLUE)
        assertNotNull(withTimeout(TIMEOUT_MS) { repository.observeList(id).first { it != null } })

        repository.softDeleteList(id)

        val afterDelete = withTimeout(TIMEOUT_MS) { repository.observeList(id).first { it == null } }
        assertNull(afterDelete)
    }

    // --- field-scoped mutation (DEC-003d) --------------------------------------------

    @Test
    fun updateListPatchesOnlyItsOwnFieldsAndNeverTouchesCounters(): Unit = runBlocking {
        val id = repository.createList("Groceries", ListIcon.CART, ListColor.PRIMARY_BLUE)
        // Simulate a concurrent counter update this repository never issues itself
        // (that mutation belongs to FB-204/item operations) landing between create and
        // this update - a whole-document set() would silently wipe it back out.
        listDoc(id).update(mapOf("totalItems" to 5L, "completedItems" to 2L)).awaitResult()

        repository.updateList(id, "Weekly Groceries", ListIcon.FOOD, ListColor.ROSE)

        val raw = listDoc(id).get().awaitResult()
        assertEquals("Weekly Groceries", raw.getString("name"))
        assertEquals("FOOD", raw.getString("icon"))
        assertEquals("ROSE", raw.getString("color"))
        assertEquals(5L, raw.getLong("totalItems"), "a field-scoped patch must not clobber an untouched field")
        assertEquals(2L, raw.getLong("completedItems"), "a field-scoped patch must not clobber an untouched field")
    }

    @Test
    fun softDeleteThenRestoreRoundTripsThroughTheLiveView(): Unit = runBlocking {
        val id = repository.createList("Groceries", ListIcon.CART, ListColor.PRIMARY_BLUE)

        repository.softDeleteList(id)
        val tombstoned = listDoc(id).get().awaitResult()
        assertNotNull(tombstoned.getTimestamp("deletedAt"))
        assertNull(withTimeout(TIMEOUT_MS) { repository.observeList(id).first { it == null } })

        repository.restoreList(id)

        val restored = listDoc(id).get().awaitResult()
        assertTrue(restored.contains("deletedAt"))
        assertNull(restored.get("deletedAt"), "restore must clear deletedAt, not merely leave it stale")
        val reappeared = withTimeout(TIMEOUT_MS) { repository.observeList(id).first { it != null } }
        assertEquals(id, reappeared?.id)
    }

    // --- error mapping under real Rules ----------------------------------------------

    @Test
    fun aCrossUserWriteIsDeniedAndSurfacesAsAForbiddenApplicationErrorNotAnSdkException(): Unit = runBlocking {
        val someoneElsesUid = "not-$uid"
        val crossUserRepository = AndroidFirebaseListRepository(firestore, CurrentUidProvider { someoneElsesUid })

        val failure = runCatching {
            crossUserRepository.createList("Not mine", ListIcon.CART, ListColor.PRIMARY_BLUE)
        }

        assertTrue(failure.isFailure)
        val exception = assertIs<ListRepositoryException>(failure.exceptionOrNull())
        assertEquals(RepositoryErrorCode.FORBIDDEN, exception.error.code)
    }

    // --- FB-202's own listener-cancellation obligation -------------------------------

    @Test
    fun cancellingTheCollectorReleasesTheRealFirestoreListener(): Unit = runBlocking {
        val seen = mutableListOf<List<FluxListSummary>>()
        val collector = repository.observeListSummaries().onEach { seen += it }.launchIn(scope)
        withTimeout(TIMEOUT_MS) { while (seen.isEmpty()) yield() }
        val beforeCancellation = seen.size

        collector.cancelAndJoin()

        // A real Firestore write that the released listener must not observe. Issued
        // through a second, independent repository instance so it is unambiguously an
        // external change, not a side effect of the cancelled collector's own repository.
        val secondRepository = AndroidFirebaseListRepository(firestore, CurrentUidProvider { uid })
        secondRepository.createList("Should not be observed", ListIcon.STAR, ListColor.SKY)
        // Give a real leaked listener a fair chance to fire before asserting it did not.
        delay(2_000)

        assertEquals(
            beforeCancellation,
            seen.size,
            "a released listener must not keep pushing snapshot updates after cancellation",
        )
    }

    private fun listDoc(id: String) = firestore.collection("users").document(uid).collection("lists").document(id)

    private fun uniqueEmail(): String = "fb202-${UUID.randomUUID()}@example.test"

    private fun emulatorHost(): String = when (FirebaseEmulatorConfig.HOST) {
        "127.0.0.1", "localhost" -> ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS
        else -> FirebaseEmulatorConfig.HOST
    }

    private companion object {
        const val APP_NAME = "fb202-instrumented-test"
        /** Throwaway passphrase for emulator-only accounts; not a credential. */
        const val PASSWORD = "fb202-emulator-only"
        const val TIMEOUT_MS = 20_000L
        const val ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS = "10.0.2.2"

        /** `useEmulator` may only be called once per SDK instance. */
        var authEmulatorConfigured = false
        var firestoreEmulatorConfigured = false
    }
}

/**
 * Local `Task.await()`: this module has no `kotlinx-coroutines-play-services`
 * dependency (the production adapters roll their own for the same reason - see
 * `FirebaseAuthGateway.kt`'s `awaitCompletion()`), so this test file does too.
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
