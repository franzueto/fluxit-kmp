package com.fluxit.feature.listdetail

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxit.config.FirebaseEmulatorConfig
import com.fluxit.data.remote.RepositoryErrorCode
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
 * `FB-409` live, real-emulator reproduction of `FB-406-B1`/`DEC-009`'s finding (a real Firestore
 * mutation failure - `PERMISSION_DENIED` - crashing the app process because
 * [ListDetailViewModel.deleteList] was a bare `viewModelScope.launch { ... }` with no
 * `try/catch/finally` at all) and this task's fix.
 *
 * Exact counterpart of [com.fluxit.feature.dashboard.DashboardViewModelListenerCrashInstrumentedTest]
 * (`FB-408`), except the trigger is a one-shot mutation (`softDeleteList`) rather than a
 * continuously-collected listener - see that class's KDoc for the full rationale of the
 * cross-uid trigger technique and why a crashing pre-fix run is not kept in the regular
 * `connectedDebugAndroidTest` suite. This file must only ever be run on its own (`--tests`
 * filtered to this class), never as part of the full suite, when exercising the pre-fix path.
 *
 * **How the crash is triggered:** [ListDetailViewModel] is constructed with a plain
 * [AndroidFirebaseListRepository]/[AndroidFirebaseItemRepository] pair whose [CurrentUidProvider]
 * points at a *different* uid than the one actually signed in. Under this repo's owner-only
 * Firestore Rules (`request.auth.uid == uid`), `deleteList()`'s `softDeleteList(listId)` call
 * (`applyPatch` -> `update()`) then receives a genuine `PERMISSION_DENIED` from the real
 * Firestore emulator - exactly `FB-406-B1`'s finding, live.
 *
 * **Pre-fix / post-fix usage (see the `FB-409` developer report for the exact commands run):**
 * run [deleteListFailureSurfacesAForbiddenErrorInsteadOfCrashing] once against the pre-`FB-409`
 * `ListDetailViewModel.kt` (bare `launch`, no guard), which is expected to fail this test
 * process with an uncaught `RepositoryException`/`FATAL EXCEPTION`; then run it again
 * against the post-`FB-409` `ListDetailViewModel.kt` (with the try/catch/finally +
 * duplicate-submit guard), which is expected to pass, with a non-retryable [ListDetailOperationError]
 * observed instead of a crash.
 */
@RunWith(AndroidJUnit4::class)
class ListDetailViewModelDeleteListCrashInstrumentedTest {

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
                    .setApiKey("fb409-instrumented-test-key")
                    .setApplicationId("1:0:android:fb409")
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

    /** `FB-409` acceptance evidence - see this class's KDoc for the pre-fix/post-fix run
     * instructions. Named to describe the *post-fix* expectation (the state this test asserts),
     * since a test cannot assert its own pre-fix crash - the crash itself, and the logcat
     * `FATAL EXCEPTION` it produces, is the pre-fix evidence, captured out-of-band. */
    @Test
    fun deleteListFailureSurfacesAForbiddenErrorInsteadOfCrashing(): Unit = runBlocking {
        val someoneElsesUid = "not-$uid"
        val crossUidListRepository = AndroidFirebaseListRepository(firestore, CurrentUidProvider { someoneElsesUid })
        val crossUidItemRepository = AndroidFirebaseItemRepository(firestore, CurrentUidProvider { someoneElsesUid })
        val store = ViewModelStore()
        try {
            val vm = ListDetailViewModel(
                "fb409-cross-uid-list-id",
                crossUidListRepository,
                crossUidItemRepository,
                fixedAuthRepository(uid),
            )
            store.put("vm", vm)
            val collector = scope.launch { vm.uiState.collect {} }

            vm.deleteList()

            val error = withTimeout(TIMEOUT_MS) {
                awaitOperationError(vm)
            }

            assertEquals(
                RepositoryErrorCode.FORBIDDEN,
                error.error.code,
                "a real PERMISSION_DENIED mutation failure must surface as a FORBIDDEN operation error, not crash the process",
            )
            assertFalse(error.error.canRetry, "a permission-denied delete must not be offered as retryable")
            assertEquals(ListDetailOperation.DELETE_LIST, error.operation)
            assertFalse(vm.isDeletingList.value, "isDeletingList must reset on failure (finally)")
            assertFalse(vm.uiState.value.listDeleted, "a failed delete must not close the screen")
            assertTrue(collector.isActive, "the uiState collector coroutine must survive the mutation failure")

            collector.cancel()
        } finally {
            store.clear()
        }
    }

    private suspend fun awaitOperationError(vm: ListDetailViewModel): ListDetailOperationError {
        while (vm.operationError.value == null) {
            delay(POLL_INTERVAL_MS)
        }
        return requireNotNull(vm.operationError.value)
    }

    private fun uniqueEmail(): String = "fb409-listdetail-${UUID.randomUUID()}@example.test"

    private fun emulatorHost(): String = when (FirebaseEmulatorConfig.HOST) {
        "127.0.0.1", "localhost" -> ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS
        else -> FirebaseEmulatorConfig.HOST
    }

    private companion object {
        const val APP_NAME = "fb409-listdetail-instrumented-test"
        /** Throwaway passphrase for emulator-only accounts; not a credential. */
        const val PASSWORD = "fb409-emulator-only"
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
