package com.fluxit.feature.dashboard

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxit.config.FirebaseEmulatorConfig
import com.fluxit.data.DebugSeeder
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.domain.ListRepository
import com.fluxit.domain.ScreenLoadState
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthResult
import com.fluxit.domain.auth.AuthSession
import com.fluxit.domain.auth.AuthUser
import com.fluxit.firebase.item.AndroidFirebaseItemRepository
import com.fluxit.firebase.list.AndroidFirebaseListRepository
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `FB-405` Android instrumented/emulator-backed evidence for the manual offline/denied-write
 * matrix, at the [DashboardViewModel]/[ScreenLoadState] layer rather than the raw repository
 * layer [com.fluxit.firebase.list.FirestoreListSnapshotEmulatorIntegrationTest] and
 * [com.fluxit.firebase.list.FirestoreListEmulatorIntegrationTest] already prove.
 *
 * What those two existing suites cannot show: that a *ViewModel* constructed with the real
 * [AndroidFirebaseListRepository] - i.e. the exact composition production Koin wiring uses once
 * `fluxit.firebase.repositories.enabled=true` - actually surfaces `hasPendingWrites`/
 * `isFromCache` through [DashboardUiState.loadState] (`FB-405` leg 1: airplane mode/offline
 * mutation) and how a genuine cross-user `PERMISSION_DENIED` currently surfaces through
 * [DashboardUiState.operationError] (`FB-405` leg 3: denied write).
 *
 * This class deliberately does **not** modify [DashboardViewModel] itself (`FB-404`/`FB-407`
 * are both already `DONE`) - it only observes and evidences the existing, unmodified behavior
 * against a real Auth+Firestore emulator pair, exactly as
 * [com.fluxit.firebase.list.FirestoreListSnapshotEmulatorIntegrationTest]'s KDoc frames its own
 * `disableNetwork`/`enableNetwork` use as `DEC-004`'s accepted "cut host networking"
 * approximation of airplane mode.
 *
 * Every [DashboardViewModel] built here is disposed through a real [ViewModelStore.clear] in a
 * `finally` block. This is not cosmetic: leaving one alive keeps its `viewModelScope`-backed
 * `stateIn(..., SharingStarted.WhileSubscribed(5_000), ...)` upstream - and therefore the real
 * Firestore listener beneath it - alive for up to 5 more seconds after a test's own
 * `collector.cancel()`, long enough to straddle into the next test method on the same shared
 * (by [APP_NAME]) `FirebaseApp`/`FirebaseAuth`/`FirebaseFirestore` singletons. An earlier version
 * of this suite did not do this and hit exactly that: a leaked listener from one test observed
 * the *next* test's freshly-signed-in user's own uid path under the *previous* test's now-stale
 * auth token, drew a genuine `PERMISSION_DENIED` on a listener [DashboardViewModel] never guards
 * with a `.catch`, and crashed the whole instrumented-test app process. See
 * [crossUserDeleteIsDeniedAndDashboardReportsItAsForbiddenNotRetryable]'s KDoc for
 * why that underlying crash mechanism is real and reported to the `FB-405` matrix, but is not
 * itself fixed or kept as a permanently-crashing test here.
 */
@RunWith(AndroidJUnit4::class)
class DashboardViewModelEmulatorIntegrationTest {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var uid: String
    private lateinit var repository: AndroidFirebaseListRepository
    private lateinit var itemRepository: AndroidFirebaseItemRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun connectToTheEmulatorsAndSignIn(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = FirebaseApp.getApps(context)
            .firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApiKey("fb405-instrumented-test-key")
                    .setApplicationId("1:0:android:fb405")
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
        itemRepository = AndroidFirebaseItemRepository(firestore, CurrentUidProvider { uid })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun signOutAndRelease() {
        runCatching { firestore.enableNetwork() }
        auth.signOut()
        scope.cancel()
    }

    /** A trivially-authenticated [AuthRepository]: fixed to [AuthSession.Authenticated] for
     * [uid] for this test's lifetime - [DashboardViewModel]'s [ScreenLoadState.FatalSession]
     * branch is `FB-404`'s own scope, not this task's. */
    private fun fixedAuthRepository(fixedUid: String) = object : AuthRepository {
        override val session = MutableStateFlow<AuthSession>(
            AuthSession.Authenticated(AuthUser(fixedUid, email = null)),
        )
        override suspend fun restoreSession() = Unit
        override suspend fun signUp(email: String, password: String): AuthResult = AuthResult.Success
        override suspend fun signIn(email: String, password: String): AuthResult = AuthResult.Success
        override suspend fun sendPasswordResetEmail(email: String): AuthResult = AuthResult.Success
        override suspend fun signOut(): AuthResult = AuthResult.Success
    }

    /**
     * `FB-405` leg 1 (airplane mode/offline mutation), at the ViewModel layer. Mirrors
     * [com.fluxit.firebase.list.FirestoreListSnapshotEmulatorIntegrationTest]'s
     * `disableNetwork`/`enableNetwork` shape, but asserts on [DashboardUiState.loadState]
     * rather than the raw [com.fluxit.domain.RepositorySnapshot].
     */
    @Test
    fun offlineMutationSurfacesPendingWritesInScreenLoadStateThenClearsOnReconnect(): Unit = runBlocking {
        val store = ViewModelStore()
        try {
            val vm = DashboardViewModel(repository, DebugSeeder(repository, itemRepository), fixedAuthRepository(uid))
            store.put("vm", vm)
            val collector = scope.launch { vm.uiState.collect {} }
            withTimeout(TIMEOUT_MS) { awaitState(vm) { it.loadState is ScreenLoadState.Loaded } }

            firestore.disableNetwork().awaitResult()
            // Same eventual-consistency shape as the repository-level test this mirrors: the
            // write is applied to the local cache immediately (visible to this ViewModel's
            // observation), but the underlying Task the repository awaits cannot complete
            // until the server acknowledges it - so this is launched with `async` and
            // deliberately not awaited before `enableNetwork()` below.
            val createDeferred = async(Dispatchers.IO) {
                repository.createList("FB-405 Offline List", ListIcon.CART, ListColor.PRIMARY_BLUE)
            }

            val pending = withTimeout(TIMEOUT_MS) {
                awaitState(vm) { (it.loadState as? ScreenLoadState.Loaded)?.hasPendingWrites == true }
            }
            val pendingLoad = pending.loadState as ScreenLoadState.Loaded
            assertTrue(pendingLoad.hasPendingWrites, "a locally-held write must surface as hasPendingWrites=true on DashboardUiState.loadState")
            assertTrue(pendingLoad.isFromCache, "with the SDK offline, loadState must also report isFromCache=true")
            assertTrue(pending.hasPendingWrites, "the DashboardUiState convenience property must agree with loadState")
            assertTrue(pending.isFromCache, "the DashboardUiState convenience property must agree with loadState")

            firestore.enableNetwork().awaitResult()
            val listId = withTimeout(TIMEOUT_MS) { createDeferred.await() }
            val acked = withTimeout(TIMEOUT_MS) {
                awaitState(vm) {
                    val loaded = it.loadState as? ScreenLoadState.Loaded ?: return@awaitState false
                    !loaded.hasPendingWrites && loaded.data.any { summary -> summary.list.id == listId }
                }
            }
            assertTrue(!acked.hasPendingWrites, "once the server acknowledges the write, DashboardUiState.hasPendingWrites must clear")

            collector.cancel()
        } finally {
            store.clear()
        }
    }

    /**
     * `FB-405` leg 3 (denied write), at the ViewModel layer: a real `PERMISSION_DENIED` write
     * reaches [DashboardUiState] as [RepositoryErrorCode.FORBIDDEN] with `canRetry = false`
     * (PM-01; it was previously collapsed to a retryable `UNKNOWN`).
     *
     * **Why [ObserveOwnUidButWriteAsAnotherUidListRepository] splits observe/write, rather than
     * one plain cross-user repository:** an earlier version of this test built the whole
     * [DashboardViewModel] against a single cross-user [AndroidFirebaseListRepository] (same
     * shape as [com.fluxit.firebase.list.FirestoreListEmulatorIntegrationTest]'s
     * `aCrossUserWriteIsDeniedAndSurfacesAsAForbiddenApplicationErrorNotAnSdkException`, but for
     * the whole ViewModel rather than one call). Under this codebase's current owner-only
     * Rules, a uid path is symmetrically denied for both reads and writes, so
     * `observeListSummariesSnapshot()` itself received the exact same `PERMISSION_DENIED` and
     * called `close(error.toRepositoryException())` (`AndroidFirebaseListRepository.kt`).
     * That closes the `callbackFlow` with an exception `DashboardViewModel`'s
     * `listLoadState`/`uiState` `combine` chain never catches (`FB-404` added no `.catch` to
     * it), which propagates uncaught through `viewModelScope` (`Dispatchers.Main.immediate`)
     * and **crashed the real instrumented-test app process** - reproduced live, real
     * `TestRunner`/logcat evidence: `Process: com.fluxit ... FATAL EXCEPTION ...
     * com.fluxit.data.remote.RepositoryException ... Suppressed:
     * ...StandaloneCoroutine{Cancelling}@...,Dispatchers.Main.immediate]`. That is a real,
     * independently significant finding reported alongside this task's matrix (see the
     * `FB-405` ledger evidence), but it is **not reproducible as a passing/failing JUnit
     * assertion** (the process that would report the result is what dies), it is not this
     * task's job to fix (`FB-404`'s state machine is explicitly out of `FB-405`'s scope), and
     * a deliberately-crashing test cannot stay in this suite without poisoning every other
     * instrumented test's run. [ObserveOwnUidButWriteAsAnotherUidListRepository] therefore keeps
     * `observeListSummariesSnapshot()` on the legitimate, own-uid [repository] (so the screen's
     * observation genuinely never fails - matching every real production path, where a
     * signed-in user's own uid is never denied) and routes only the mutating call under test -
     * [ListRepository.softDeleteList] - to the cross-user [AndroidFirebaseListRepository], so
     * the resulting `PERMISSION_DENIED` is caught by [DashboardViewModel.performDelete]'s
     * existing `try`/`catch` (which is unconditional, unlike the listener's uncaught `close`)
     * exactly as it would be for any other mutation failure in production today.
     */
    @Test
    fun crossUserDeleteIsDeniedAndDashboardReportsItAsForbiddenNotRetryable(): Unit = runBlocking {
        val store = ViewModelStore()
        try {
            val someoneElsesUid = "not-$uid"
            val crossUserRepository = AndroidFirebaseListRepository(firestore, CurrentUidProvider { someoneElsesUid })
            val decorated = ObserveOwnUidButWriteAsAnotherUidListRepository(observeOnly = repository, writeOnly = crossUserRepository)
            val vm = DashboardViewModel(decorated, DebugSeeder(decorated, itemRepository), fixedAuthRepository(uid))
            store.put("vm", vm)
            val collector = scope.launch { vm.uiState.collect {} }
            withTimeout(TIMEOUT_MS) { awaitState(vm) { it.loadState is ScreenLoadState.Loaded } }

            vm.deleteList("fb405-nonexistent-list-id")

            val failed = withTimeout(TIMEOUT_MS) { awaitState(vm) { it.operationError != null } }
            val operationError = requireNotNull(failed.operationError)
            assertEquals(DashboardOperation.DELETE_LIST, operationError.operation)

            assertEquals(RepositoryErrorCode.FORBIDDEN, operationError.error.code)
            assertFalse(operationError.error.canRetry, "a permission-denied write must not be offered as retryable")

            collector.cancel()
        } finally {
            store.clear()
        }
    }

    private suspend fun awaitState(
        vm: DashboardViewModel,
        predicate: (DashboardUiState) -> Boolean,
    ): DashboardUiState {
        while (!predicate(vm.uiState.value)) {
            delay(POLL_INTERVAL_MS)
        }
        return vm.uiState.value
    }

    private fun uniqueEmail(): String = "fb405-dashboard-${UUID.randomUUID()}@example.test"

    private fun emulatorHost(): String = when (FirebaseEmulatorConfig.HOST) {
        "127.0.0.1", "localhost" -> ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS
        else -> FirebaseEmulatorConfig.HOST
    }

    private companion object {
        const val APP_NAME = "fb405-dashboard-instrumented-test"
        /** Throwaway passphrase for emulator-only accounts; not a credential. */
        const val PASSWORD = "fb405-emulator-only"
        const val TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 100L
        const val ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS = "10.0.2.2"

        /** `useEmulator` may only be called once per SDK instance. */
        var authEmulatorConfigured = false
        var firestoreEmulatorConfigured = false
    }
}

/**
 * Test-only [ListRepository] decorator - see
 * [DashboardViewModelEmulatorIntegrationTest.crossUserDeleteIsDeniedAndDashboardReportsItAsForbiddenNotRetryable]'s
 * KDoc for why observation and the mutation under test are deliberately split across two real
 * [AndroidFirebaseListRepository] instances rather than sharing one. `updateList`/`createList`/
 * `restoreList` are wired to [writeOnly] too, for consistency, though only `softDeleteList` is
 * exercised by this test today.
 */
private class ObserveOwnUidButWriteAsAnotherUidListRepository(
    private val observeOnly: ListRepository,
    private val writeOnly: ListRepository,
) : ListRepository {
    override fun observeListSummaries() = observeOnly.observeListSummaries()
    override fun observeListSummariesSnapshot() = observeOnly.observeListSummariesSnapshot()
    override fun observeList(listId: String) = observeOnly.observeList(listId)
    override suspend fun createList(name: String, icon: ListIcon, color: ListColor) =
        writeOnly.createList(name, icon, color)
    override suspend fun updateList(listId: String, name: String, icon: ListIcon, color: ListColor) =
        writeOnly.updateList(listId, name, icon, color)
    override suspend fun softDeleteList(listId: String) = writeOnly.softDeleteList(listId)
    override suspend fun restoreList(listId: String) = writeOnly.restoreList(listId)
    override suspend fun purgeExpired() = observeOnly.purgeExpired()
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
