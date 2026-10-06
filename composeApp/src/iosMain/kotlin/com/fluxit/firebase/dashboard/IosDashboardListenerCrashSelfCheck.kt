package com.fluxit.firebase.dashboard

import com.fluxit.data.DebugSeeder
import com.fluxit.domain.ScreenLoadState
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthResult
import com.fluxit.domain.auth.AuthSession
import com.fluxit.domain.auth.AuthUser
import com.fluxit.feature.dashboard.DashboardUiState
import com.fluxit.feature.dashboard.DashboardViewModel
import com.fluxit.firebase.IosFirebaseEmulatorSettings
import com.fluxit.firebase.auth.IosAuthBridgeRegistry
import com.fluxit.firebase.auth.IosAuthRepository
import com.fluxit.firebase.item.IosFirebaseItemRepository
import com.fluxit.firebase.list.CurrentUidProvider
import com.fluxit.firebase.list.IosFirebaseListRepository
import com.fluxit.firebase.list.IosFirestoreListBridgeRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSUUID

/**
 * `FB-408` iOS self-check: live, real-emulator reproduction of `FB-405`'s headline finding (a
 * terminal Firestore listener error - `PERMISSION_DENIED` - crashing the app process because
 * [DashboardViewModel.listLoadState]'s `combine(...)` had no `.catch`) and this task's fix, on
 * iOS. Mirrors the Android instrumented equivalent,
 * `DashboardViewModelListenerCrashInstrumentedTest`, and reuses the same
 * `FirebaseBootstrap`-launch-argument self-check mechanism `IosFirestoreListIntegrationCheck`
 * established (`FB-103`/`FB-203`) - no Xcode test target exists in this repository for a
 * live-emulator KMP check.
 *
 * **How the crash is triggered:** a real [DashboardViewModel] is built against a real
 * [IosFirebaseListRepository] whose [CurrentUidProvider] is pinned to a *different* uid than the
 * one actually signed in. Under this repo's owner-only `firestore.rules`
 * (`request.auth.uid == uid`), `observeListSummariesSnapshot()`'s real Firestore listener then
 * receives a genuine `PERMISSION_DENIED` from the live emulator and calls
 * `close(error.toListRepositoryException())` - exactly `FB-405`'s reproduced trigger, live.
 *
 * **Why this function's own `run()` try/catch (mirroring [IosFirestoreListIntegrationCheck])
 * does not, and cannot, mask the crash this check exists to prove/guard against:** the crash
 * this check is interested in happens on a *separate* coroutine -
 * [DashboardViewModel]'s own `viewModelScope`, backed by `Dispatchers.Main.immediate` - which is
 * not a structured child of this function's call stack. Pre-`FB-408`, that coroutine's uncaught
 * exception crashes the whole process asynchronously, independent of this function's own
 * try/catch, exactly mirroring the real production crash mechanism `FB-405` found. Post-`FB-408`,
 * [DashboardViewModel.listLoadState]'s `.catch` maps it to [ScreenLoadState.FatalSession]
 * instead, and this function returns a normal report.
 *
 * **Pre-fix/post-fix usage (see the `FB-408` developer report for the exact commands run):**
 * launch the real app with `-FluxItDashboardListenerCrashSelfCheck` once against the pre-`FB-408`
 * `DashboardViewModel.kt` (no `.catch`) - `xcrun simctl launch --console-pty` is expected to show
 * the process terminate/crash instead of printing this check's `FB-408 END` report line; then
 * rebuild against the post-`FB-408` `DashboardViewModel.kt` (with `.catch`) and re-launch, which
 * is expected to print `FB-408 iOS listener-crash self-check: PASSED` with
 * `loadState=FatalSession`.
 */
object IosDashboardListenerCrashSelfCheck {

    private const val PASSWORD = "fb408-emulator-only"
    private const val SETTLE_MS = 400L
    private const val AWAIT_TIMEOUT_MS = 20_000L
    private const val POLL_INTERVAL_MS = 100L

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

    suspend fun run(): String {
        if (!IosFirebaseEmulatorSettings.enabled) {
            return "FB-408 iOS listener-crash self-check: SKIPPED (emulator mode disabled)\nFB-408 END"
        }
        val authBridge = IosAuthBridgeRegistry.bridgeOrNull()
        val listBridge = IosFirestoreListBridgeRegistry.bridgeOrNull()
        if (authBridge == null || listBridge == null) {
            return "FB-408 iOS listener-crash self-check: FAILED (bridge registration missing: " +
                "authBridge=$authBridge listBridge=$listBridge)\nFB-408 END"
        }

        val auth = IosAuthRepository()
        val suffix = NSUUID().UUIDString().lowercase()
        val email = "fb408-ios-$suffix@example.com"
        val signUpResult = auth.signUp(email, PASSWORD)
        if (signUpResult !is AuthResult.Success) {
            return "FB-408 iOS listener-crash self-check: FAILED (signUp: $signUpResult)\nFB-408 END"
        }
        delay(SETTLE_MS)
        val uid = authBridge.currentUser()?.uid
            ?: return "FB-408 iOS listener-crash self-check: FAILED (no uid after signUp)\nFB-408 END"

        val someoneElsesUid = "not-$uid"
        val crossUidListRepository = IosFirebaseListRepository({ listBridge }, CurrentUidProvider { someoneElsesUid })
        val crossUidItemRepository = IosFirebaseItemRepository()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vm = DashboardViewModel(
                crossUidListRepository,
                DebugSeeder(crossUidListRepository, crossUidItemRepository),
                fixedAuthRepository(uid),
            )
            val collector = scope.launch { vm.uiState.collect {} }

            var waited = 0L
            while (vm.uiState.value.loadState is ScreenLoadState.Loading) {
                if (waited >= AWAIT_TIMEOUT_MS) {
                    return "FB-408 iOS listener-crash self-check: FAILED (timed out still Loading - " +
                        "the listener error may not have reached the emulator yet)\nFB-408 END"
                }
                delay(POLL_INTERVAL_MS)
                waited += POLL_INTERVAL_MS
            }

            val state: DashboardUiState = vm.uiState.value
            collector.cancel()
            auth.signOut()

            return if (state.loadState is ScreenLoadState.FatalSession && state.isFatalSession) {
                "FB-408 iOS listener-crash self-check: PASSED (loadState=FatalSession, no crash)\nFB-408 END"
            } else {
                "FB-408 iOS listener-crash self-check: FAILED (unexpected loadState=${state.loadState})\nFB-408 END"
            }
        } finally {
            scope.cancel()
        }
    }
}
