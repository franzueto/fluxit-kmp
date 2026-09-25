package com.fluxit.firebase.list

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxit.config.FirebaseEmulatorConfig
import com.fluxit.domain.RepositorySnapshot
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `FB-407` Android instrumented/emulator-backed proof that
 * [AndroidFirebaseListRepository.observeListSummariesSnapshot] reports the *real*
 * Firestore SDK's `QuerySnapshot.metadata.isFromCache`/`hasPendingWrites()` signal, not
 * the [com.fluxit.domain.ListRepository] interface's fresh-reporting default - the exact
 * gap `FB-404`'s reviewer found (`DEC-007`) and this task exists to close.
 *
 * What a fake-repository unit test (`FB-404`'s `ViewModelTests.kt`) cannot prove: that
 * [AndroidFirebaseListRepository]'s new `MetadataChanges.INCLUDE`-registered listener
 * genuinely receives `isFromCache=true`/`hasPendingWrites=true` from a live Firestore SDK
 * instance while a write is only held locally, and that both flags clear once the SDK
 * reconnects and the server acknowledges the write. This test forces that condition with
 * [FirebaseFirestore.disableNetwork]/[FirebaseFirestore.enableNetwork] against the real
 * emulator - the same "cut host networking" fidelity-limited approximation of airplane
 * mode `DEC-004` already accepts for `FB-405`'s manual matrix, not a genuine radio toggle.
 *
 * Same prerequisites as [FirestoreListEmulatorIntegrationTest] (an Android
 * emulator/device reaching the host at `10.0.2.2`, plus the Auth and Firestore emulators
 * running on the host - `firebase emulators:start --only auth,firestore --project
 * demo-fluxit` from the repository's `firebase/` directory). Run with:
 * `./gradlew :composeApp:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class FirestoreListSnapshotEmulatorIntegrationTest {

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
                    .setApiKey("fb407-instrumented-test-key")
                    .setApplicationId("1:0:android:fb407")
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
        // Best-effort: leave the network enabled for the next test in the suite even if
        // an assertion above threw mid-test.
        runCatching { firestore.enableNetwork() }
        auth.signOut()
        scope.cancel()
    }

    @Test
    fun observeListSummariesSnapshotReportsRealPendingWritesAndCacheMetadata(): Unit = runBlocking {
        val emissions = mutableListOf<RepositorySnapshot<List<com.fluxit.domain.FluxListSummary>>>()
        val collector = repository.observeListSummariesSnapshot().onEach { emissions += it }.launchIn(scope)
        withTimeout(TIMEOUT_MS) { awaitEmission(emissions) { true } }

        // Force the SDK offline against the real emulator connection, then write.
        //
        // FirebaseFirestore semantics (confirmed by this package's own FB-204
        // `addItemQueuedWhileOfflineCommitsOnceNetworkIsRestored` precedent): the write is
        // applied to the local cache - and therefore visible to this listener -
        // immediately, but the Task `AndroidFirebaseListRepository.createList` awaits
        // does NOT complete until the server acknowledges it, which cannot happen while
        // offline. So `createList` is launched with `async` here and deliberately not
        // awaited yet - awaiting it before `enableNetwork()` below would hang this test
        // forever, exactly as it did the first time this test was written without this
        // `async`.
        firestore.disableNetwork().awaitResult()
        val createDeferred = async(Dispatchers.IO) {
            repository.createList("FB-407 Offline List", com.fluxit.domain.ListIcon.CART, com.fluxit.domain.ListColor.PRIMARY_BLUE)
        }

        // Note: the new document itself is deliberately NOT expected to appear in
        // `pendingSeen.value` yet. Its `createdAt`/`updatedAt` are `FieldValue.
        // serverTimestamp()` sentinels (`FirebaseValue.PendingServerTimestamp`) that
        // decode as null until the server resolves them - which cannot happen while
        // offline - so FB-201's `FirebaseDocumentMapper.list` correctly treats it as
        // malformed (`MISSING_FIELD`) and drops it from the mapped list, exactly as it
        // would for any other client racing to read its own unacknowledged write (the
        // same eventual-consistency window `IosFirestoreListIntegrationCheck.
        // awaitCondition`'s KDoc documents on the other platform). What this test proves
        // is the *snapshot-level* `hasPendingWrites`/`isFromCache` metadata - which
        // Firestore computes over the whole query result, independent of this
        // repository's own field-validity filtering - correctly reaches
        // [RepositorySnapshot] while that write is outstanding.
        val pendingSeen = withTimeout(TIMEOUT_MS) { awaitEmission(emissions) { it.hasPendingWrites } }
        assertTrue(pendingSeen.hasPendingWrites, "a write held only in the local cache must report hasPendingWrites=true")
        assertTrue(pendingSeen.isFromCache, "with the SDK offline, the snapshot must also report isFromCache=true")

        // Reconnect, let the queued write actually commit, then wait for the server to
        // acknowledge it - the same document must eventually be reported with
        // hasPendingWrites=false.
        firestore.enableNetwork().awaitResult()
        val listId = withTimeout(TIMEOUT_MS) { createDeferred.await() }
        val acked = withTimeout(TIMEOUT_MS) {
            awaitEmission(emissions) { !it.hasPendingWrites && it.value.any { summary -> summary.list.id == listId } }
        }
        assertTrue(!acked.hasPendingWrites, "once the server acknowledges the write, hasPendingWrites must clear")
        assertEquals(listOf(listId), acked.value.filter { it.list.id == listId }.map { it.list.id })

        collector.cancel()
    }

    /**
     * Polls [emissions] (mutated on the Firestore listener's own callback thread) until
     * its most recent element satisfies [predicate], re-checking every
     * [POLL_INTERVAL_MS]. The caller wraps this in [withTimeout] - see
     * `IosFirestoreListIntegrationCheck.awaitCondition`'s identical rationale for why a
     * fixed settle delay is not reliable for Firestore round trips, even against a local
     * emulator.
     */
    private suspend fun awaitEmission(
        emissions: List<RepositorySnapshot<List<com.fluxit.domain.FluxListSummary>>>,
        predicate: (RepositorySnapshot<List<com.fluxit.domain.FluxListSummary>>) -> Boolean,
    ): RepositorySnapshot<List<com.fluxit.domain.FluxListSummary>> {
        while (emissions.lastOrNull()?.let(predicate) != true) {
            delay(POLL_INTERVAL_MS)
        }
        return emissions.last()
    }

    private fun uniqueEmail(): String = "fb407-list-${UUID.randomUUID()}@example.test"

    private fun emulatorHost(): String = when (FirebaseEmulatorConfig.HOST) {
        "127.0.0.1", "localhost" -> ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS
        else -> FirebaseEmulatorConfig.HOST
    }

    private companion object {
        const val APP_NAME = "fb407-list-instrumented-test"
        /** Throwaway passphrase for emulator-only accounts; not a credential. */
        const val PASSWORD = "fb407-emulator-only"
        const val TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 100L
        const val ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS = "10.0.2.2"

        /** `useEmulator` may only be called once per SDK instance. */
        var authEmulatorConfigured = false
        var firestoreEmulatorConfigured = false
    }
}

/** Local `Task.await()` - see `FirestoreListEmulatorIntegrationTest.kt`'s identical helper's KDoc. */
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
