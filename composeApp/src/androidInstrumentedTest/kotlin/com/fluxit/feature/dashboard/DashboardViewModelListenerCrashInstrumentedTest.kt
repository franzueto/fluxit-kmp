package com.fluxit.feature.dashboard

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxit.config.FirebaseEmulatorConfig
import com.fluxit.data.DebugSeeder
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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * live, real-emulator reproduction of the headline finding (a terminal Firestore
 * listener error - `PERMISSION_DENIED` - crashing the app process because
 * [DashboardViewModel.listLoadState]'s `combine(...)` had no `.catch`) and this task's fix.
 *
 * This is the same crash mechanism [DashboardViewModelEmulatorIntegrationTest]'s KDoc already
 * documents as live-reproduced during the development ("an earlier version of this test
 * ... crashed the real instrumented-test app process ... real TestRunner/logcat evidence:
 * `Process: com.fluxit ... FATAL EXCEPTION ... RepositoryException ...
 * Dispatchers.Main.immediate`") - that file deliberately does not keep a crashing test in its own
 * suite (a crash there would poison every other instrumented test's run). This file exists
 * specifically to reproduce that same crash live, on demand, against the real Auth+Firestore
 * emulator pair, for the pre-fix/post-fix acceptance evidence - it must only ever be run
 * on its own (`--tests` filtered to this class), never as part of the full
 * `connectedDebugAndroidTest` suite, for exactly that reason when exercising the pre-fix path.
 *
 * **How the crash is triggered:** [DashboardViewModel] is constructed with a plain
 * [AndroidFirebaseListRepository] whose [CurrentUidProvider] points at a *different* uid than
 * the one actually signed in. Under this repo's owner-only Firestore Rules
 * (`request.auth.uid == uid`), `observeListSummariesSnapshot()`'s `addSnapshotListener` then
 * receives a genuine `PERMISSION_DENIED` from the real Firestore emulator and calls
 * `close(error.toRepositoryException)` - exactly the headline finding's trigger,
 * live.
 *
 * **Pre-fix / post-fix usage:**
 * run [crossUidObservationEitherCrashesOrSurfacesFatalSession] once against the pre-fix
 * `DashboardViewModel.kt` (no `.catch`) with logcat capturing, which is expected to fail this
 * test process with an uncaught `RepositoryException`/`FATAL EXCEPTION`; then run it again
 * against the post-fix `DashboardViewModel.kt` (with `.catch`), which is expected to pass,
 * with [DashboardUiState.loadState] observed as [ScreenLoadState.FatalSession].
 */
@RunWith(AndroidJUnit4::class)
class DashboardViewModelListenerCrashInstrumentedTest {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var uid: String
    private lateinit var scope: CoroutineScope

    @Before
    fun connectToTheEmulatorsAndSignIn(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = FirebaseApp.getApps(context)
            .firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApiKey("listenercrash-instrumented-test-key")
                    .setApplicationId("1:0:android:listenercrash")
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
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun signOutAndRelease() {
        auth.signOut()
        scope.cancel()
    }

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

    /** acceptance evidence - see this class's KDoc for the pre-fix/post-fix run
     * instructions. Named to describe the *post-fix* expectation (the state this test asserts),
     * since a test cannot assert its own pre-fix crash - the crash itself, and the logcat
     * `FATAL EXCEPTION` it produces, is the pre-fix evidence, captured out-of-band. */
    @Test
    fun crossUidObservationSurfacesFatalSessionInsteadOfCrashing(): Unit = runBlocking {
        val someoneElsesUid = "not-$uid"
        val crossUidRepository = AndroidFirebaseListRepository(firestore, CurrentUidProvider { someoneElsesUid })
        val itemRepository = AndroidFirebaseItemRepository(firestore, CurrentUidProvider { someoneElsesUid })
        val store = ViewModelStore()
        try {
            val vm = DashboardViewModel(
                crossUidRepository,
                DebugSeeder(crossUidRepository, itemRepository),
                fixedAuthRepository(uid),
            )
            store.put("vm", vm)
            val collector = scope.launch { vm.uiState.collect {} }

            val state = withTimeout(TIMEOUT_MS) {
                awaitState(vm) { it.loadState !is ScreenLoadState.Loading }
            }

            assertIs<ScreenLoadState.FatalSession>(
                state.loadState,
                "a terminal (cross-uid PERMISSION_DENIED) listener error must surface as FatalSession, not crash the process",
            )
            assertTrue(state.isFatalSession)
            assertTrue(collector.isActive, "the uiState collector coroutine must survive the listener error")

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

    private fun uniqueEmail(): String = "listenercrash-dashboard-${UUID.randomUUID()}@example.test"

    private fun emulatorHost(): String = when (FirebaseEmulatorConfig.HOST) {
        "127.0.0.1", "localhost" -> ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS
        else -> FirebaseEmulatorConfig.HOST
    }

    private companion object {
        const val APP_NAME = "listenercrash-dashboard-instrumented-test"
        /** Throwaway passphrase for emulator-only accounts; not a credential. */
        const val PASSWORD = "listenercrash-emulator-only"
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
