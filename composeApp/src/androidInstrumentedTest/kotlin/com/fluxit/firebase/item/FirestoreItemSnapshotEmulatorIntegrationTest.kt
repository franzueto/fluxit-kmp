package com.fluxit.firebase.item

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxit.config.FirebaseEmulatorConfig
import com.fluxit.domain.FluxItem
import com.fluxit.domain.RepositorySnapshot
import com.fluxit.firebase.list.CurrentUidProvider
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
 * [AndroidFirebaseItemRepository.observeItemsSnapshot] reports the *real* Firestore SDK's
 * `QuerySnapshot.metadata.isFromCache`/`hasPendingWrites()` signal - the item-repository
 * counterpart of `com.fluxit.firebase.list.FirestoreListSnapshotEmulatorIntegrationTest`.
 * See that file's KDoc for the full rationale (identical here, scoped to items).
 *
 * Reuses the same `disableNetwork()`/`enableNetwork()` offline-simulation technique this
 * package's own `FirestoreItemEmulatorIntegrationTest.addItemQueuedWhileOfflineCommitsOnceNetworkIsRestored`
 * already established, rather than inventing a new one.
 *
 * Run with: `./gradlew :composeApp:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class FirestoreItemSnapshotEmulatorIntegrationTest {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var uid: String
    private lateinit var repository: AndroidFirebaseItemRepository
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
                    .setApplicationId("1:0:android:snapshot-item")
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
            // Defensive, mirrors FirestoreItemEmulatorIntegrationTest's identically
            // reasoned re-assertion: a previous test in this class may have left the
            // network disabled.
            enableNetwork().awaitResult()
        }
        auth.createUserWithEmailAndPassword(uniqueEmail(), PASSWORD).awaitResult()
        uid = requireNotNull(auth.currentUser?.uid) { "sign-up did not resolve a uid" }
        repository = AndroidFirebaseItemRepository(firestore, CurrentUidProvider { uid })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun signOutAndRelease() {
        runCatching { firestore.enableNetwork() }
        auth.signOut()
        scope.cancel()
    }

    @Test
    fun observeItemsSnapshotReportsRealPendingWritesAndCacheMetadata(): Unit = runBlocking {
        val listId = bootstrapList()
        val emissions = mutableListOf<RepositorySnapshot<List<FluxItem>>>()
        val collector = repository.observeItemsSnapshot(listId).onEach { emissions += it }.launchIn(scope)
        withTimeout(TIMEOUT_MS) { awaitEmission(emissions) { true } }

        // See `FirestoreListSnapshotEmulatorIntegrationTest`'s identically-reasoned
        // comment: `addItem`'s underlying Task does not complete until the server
        // acknowledges it, which cannot happen while offline, so it is launched here and
        // deliberately not joined until after `enableNetwork()` below.
        firestore.disableNetwork().awaitResult()
        val addDeferred = async(Dispatchers.IO) { repository.addItem(listId, "Offline Item") }

        // Pending server timestamp estimates must expose the real new item.
        val pendingSeen = withTimeout(TIMEOUT_MS) { awaitEmission(emissions) {
            it.hasPendingWrites && it.value.any { item -> item.title == "Offline Item" }
        } }
        val pendingItem = pendingSeen.value.single { it.title == "Offline Item" }
        val individual = withTimeout(TIMEOUT_MS) { repository.observeItem(listId, pendingItem.id).first { it != null } }
        assertEquals("Offline Item", individual?.title)
        assertTrue(!addDeferred.isCompleted, "the queued creation must still await server acknowledgement")
        assertTrue(pendingSeen.hasPendingWrites, "a write held only in the local cache must report hasPendingWrites=true")
        assertTrue(pendingSeen.isFromCache, "with the SDK offline, the snapshot must also report isFromCache=true")

        firestore.enableNetwork().awaitResult()
        withTimeout(TIMEOUT_MS) { addDeferred.await() }
        val acked = withTimeout(TIMEOUT_MS) {
            awaitEmission(emissions) { !it.hasPendingWrites && it.value.any { item -> item.title == "Offline Item" } }
        }
        assertTrue(!acked.hasPendingWrites, "once the server acknowledges the write, hasPendingWrites must clear")
        assertEquals(1, acked.value.count { it.title == "Offline Item" })

        collector.cancel()
    }

    private suspend fun awaitEmission(
        emissions: List<RepositorySnapshot<List<FluxItem>>>,
        predicate: (RepositorySnapshot<List<FluxItem>>) -> Boolean,
    ): RepositorySnapshot<List<FluxItem>> {
        while (emissions.lastOrNull()?.let(predicate) != true) {
            delay(POLL_INTERVAL_MS)
        }
        return emissions.last()
    }

    private fun listDoc(id: String) = firestore.collection("users").document(uid).collection("lists").document(id)

    /** Mirrors `FirestoreItemEmulatorIntegrationTest.bootstrapList`'s field set exactly. */
    private suspend fun bootstrapList(): String {
        val id = UUID.randomUUID().toString()
        listDoc(id).set(
            mapOf(
                "name" to "Groceries",
                "icon" to "CART",
                "color" to "PRIMARY_BLUE",
                "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "deletedAt" to null,
                "totalItems" to 0L,
                "completedItems" to 0L,
                "schemaVersion" to 1L,
            ),
        ).awaitResult()
        return id
    }

    private fun uniqueEmail(): String = "snapshot-item-${UUID.randomUUID()}@example.test"

    private fun emulatorHost(): String = when (FirebaseEmulatorConfig.HOST) {
        "127.0.0.1", "localhost" -> ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS
        else -> FirebaseEmulatorConfig.HOST
    }

    private companion object {
        const val APP_NAME = "snapshot-item-instrumented-test"
        /** Throwaway passphrase for emulator-only accounts; not a credential. */
        const val PASSWORD = "snapshot-emulator-only"
        const val TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 100L
        const val ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS = "10.0.2.2"

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
