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
import kotlinx.coroutines.flow.first
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
 * Android instrumented/emulator-backed proof that
 * [AndroidFirebaseListRepository.observeListSummariesSnapshot] reports the *real*
 * Firestore SDK's `QuerySnapshot.metadata.isFromCache`/`hasPendingWrites()` signal, not
 * the [com.fluxit.domain.ListRepository] interface's fresh-reporting default.
 *
 * What a fake-repository unit test (the `ViewModelTests.kt`) cannot prove: that
 * [AndroidFirebaseListRepository]'s new `MetadataChanges.INCLUDE`-registered listener
 * genuinely receives `isFromCache=true`/`hasPendingWrites=true` from a live Firestore SDK
 * instance while a write is only held locally, and that both flags clear once the SDK
 * reconnects and the server acknowledges the write. This test forces that condition with
 * [FirebaseFirestore.disableNetwork]/[FirebaseFirestore.enableNetwork] against the real
 * emulator - the same "cut host networking" fidelity-limited approximation of airplane
 * mode already accepts for the manual matrix, not a genuine radio toggle.
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
                    .setApiKey("snapshot-instrumented-test-key")
                    .setApplicationId("1:0:android:snapshot")
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
        // FirebaseFirestore semantics (confirmed by this package's own 
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
            repository.createList("Offline List", com.fluxit.domain.ListIcon.CART, com.fluxit.domain.ListColor.PRIMARY_BLUE)
        }

        // Pending server timestamps use SDK estimates, so real queued
        // content must be visible before reconnect, not just metadata on an empty list.
        val pendingSeen = withTimeout(TIMEOUT_MS) { awaitEmission(emissions) {
            it.hasPendingWrites && it.value.any { row -> row.list.name == "Offline List" }
        } }
        val pendingList = pendingSeen.value.single { it.list.name == "Offline List" }.list
        val individual = withTimeout(TIMEOUT_MS) { repository.observeList(pendingList.id).first { it != null } }
        assertEquals("Offline List", individual?.name)
        assertTrue(!createDeferred.isCompleted, "the queued creation must still await server acknowledgement")
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

    private fun uniqueEmail(): String = "snapshot-list-${UUID.randomUUID()}@example.test"

    private fun emulatorHost(): String = when (FirebaseEmulatorConfig.HOST) {
        "127.0.0.1", "localhost" -> ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS
        else -> FirebaseEmulatorConfig.HOST
    }

    private companion object {
        const val APP_NAME = "snapshot-list-instrumented-test"
        /** Throwaway passphrase for emulator-only accounts; not a credential. */
        const val PASSWORD = "snapshot-emulator-only"
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
