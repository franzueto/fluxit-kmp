package com.fluxit

import com.fluxit.domain.auth.*
import com.fluxit.domain.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCleanupTests {
    private class Cleanup : SessionCleanup {
        override var pending = false
        var fail = false
        var failBegin = false
        var failComplete = false
        var afterComplete: () -> Unit = {}
        var hold: CompletableDeferred<Unit>? = null
        var clears = 0
        val events = mutableListOf<String>()
        override fun begin() { if (failBegin) error("marker write failed"); pending = true; events += "begin" }
        override suspend fun clear() {
            clears++
            events += "clear"
            hold?.await()
            if (fail) error("local cleanup failure")
        }
        override fun complete() { if (failComplete) error("marker removal failed"); pending = false; events += "complete"; afterComplete() }
    }
    private class Auth : AuthRepository {
        val state = MutableStateFlow<AuthSession>(AuthSession.Authenticated(AuthUser("A", null)))
        var restores = 0
        var outs = 0
        var outFailure = false
        var resolutionFailure = false
        var signInHold: CompletableDeferred<Unit>? = null
        override val session: Flow<AuthSession> = state
        override suspend fun restoreSession() { restores++; if (resolutionFailure) state.value = AuthSession.ResolutionFailed(AuthError.SessionExpired) }
        override suspend fun signOut(): AuthResult {
            outs++
            if (outFailure) return AuthResult.Failure(AuthError.Unknown)
            state.value = AuthSession.SignedOut
            return AuthResult.Success
        }
        override suspend fun signIn(email: String, password: String): AuthResult {
            signInHold?.await()
            state.value = AuthSession.Authenticated(AuthUser(email, null))
            return AuthResult.Success
        }
        override suspend fun signUp(email: String, password: String) = signIn(email, password)
        override suspend fun sendPasswordResetEmail(email: String) = AuthResult.Success
    }

    @Test fun rawCachedCredentialCannotUnlockBeforeRestore() = runTest {
        val raw = Auth(); val cleanup = Cleanup(); val work = SessionWork()
        val auth = SessionAuthRepository(raw, work, cleanup)
        val states = mutableListOf<AuthSession>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { auth.session.toList(states) }
        runCurrent()
        assertEquals(listOf<AuthSession>(AuthSession.Unresolved), states)
        assertFailsWith<CancellationException> { work.run { error("must not run") } }
        auth.restoreSession()
        runCurrent()
        assertIs<AuthSession.Authenticated>(states.last())
        assertEquals("open", work.run { "open" })
    }

    @Test fun quiescesListenersMutationsAndPhotoJobsBeforeClear() = runTest {
        val raw = Auth(); val cleanup = Cleanup(); val work = SessionWork(); val auth = SessionAuthRepository(raw, work, cleanup)
        auth.restoreSession()
        var listeners = 0
        var lateRef = false
        val stream = work.observe<Unit> { flow { listeners++; try { awaitCancellation() } finally { listeners-- } } }
        val collector = launch { stream.collect() }
        val mutation = launch { work.run { awaitCancellation() } }
        val upload = launch { work.run { try { awaitCancellation() } finally { cleanup.events += "photo-released" }; lateRef = true } }
        runCurrent()
        assertEquals(1, listeners)
        assertEquals(AuthResult.Success, auth.signOut())
        assertTrue(collector.isCancelled && mutation.isCancelled && upload.isCancelled)
        assertEquals(0, listeners)
        assertFalse(lateRef)
        assertTrue(cleanup.events.indexOf("photo-released") < cleanup.events.indexOf("clear"))
        assertFalse(cleanup.pending)
        assertFailsWith<CancellationException> { work.run { error("must not run") } }
        assertEquals(AuthResult.Success, auth.signIn("B", "unused"))
        assertEquals("reused", work.run { "reused" })
    }

    @Test fun staleFlowCannotResolvePathsInNewSession() = runTest {
        val work = SessionWork(); work.open()
        var started = 0
        val stale = work.observe { flow { started++; emit("A") } }
        work.close(); work.open()
        assertFailsWith<CancellationException> { stale.first() }
        assertEquals(0, started)
        assertEquals("B", work.observe { flowOf("B") }.first())
    }

    @Test fun failedClearBlocksAuthAndPersistsRetryAcrossRepositoryRestart() = runTest {
        val raw = Auth(); val cleanup = Cleanup().apply { fail = true }; val work = SessionWork()
        val auth = SessionAuthRepository(raw, work, cleanup)
        auth.restoreSession()
        assertEquals(AuthResult.Failure(AuthError.CleanupFailed), auth.signOut())
        assertTrue(cleanup.pending)
        assertEquals(0, raw.outs)
        assertEquals(AuthResult.Failure(AuthError.CleanupFailed), auth.signIn("B", "unused"))
        val restarted = SessionAuthRepository(raw, SessionWork(), cleanup)
        raw.restores = 0
        cleanup.fail = false
        restarted.restoreSession()
        assertEquals(0, raw.restores, "Pending cleanup must never validate a credential over the network")
        assertEquals(AuthSession.SignedOut, restarted.session.first())
        assertFalse(cleanup.pending)
        assertEquals(AuthResult.Success, restarted.signIn("B", "unused"))
        assertEquals("B", (restarted.session.first() as AuthSession.Authenticated).user.uid)
    }

    @Test fun authSignOutFailureKeepsCleanupPendingAndRawCallbackCannotReopen() = runTest {
        val raw = Auth(); val cleanup = Cleanup(); val work = SessionWork(); val auth = SessionAuthRepository(raw, work, cleanup)
        val states = mutableListOf<AuthSession>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { auth.session.toList(states) }
        auth.restoreSession(); raw.outFailure = true
        assertEquals(AuthResult.Failure(AuthError.CleanupFailed), auth.signOut())
        raw.state.value = AuthSession.Authenticated(AuthUser("late-A", null)); runCurrent()
        assertEquals(AuthSession.ResolutionFailed(AuthError.CleanupFailed), states.last())
        assertTrue(cleanup.pending)
        raw.outFailure = false
        assertEquals(AuthResult.Success, auth.signOut())
        assertFalse(cleanup.pending)
    }

    @Test fun cleanupTimeoutIsFailureAndRetryFinishes() = runTest {
        val raw = Auth(); val cleanup = Cleanup().apply { hold = CompletableDeferred() }; val work = SessionWork()
        val auth = SessionAuthRepository(raw, work, cleanup)
        val result = async { auth.signOut() }
        advanceTimeBy(SessionAuthRepository.CleanupTimeoutMillis.toLong()); runCurrent()
        assertEquals(AuthResult.Failure(AuthError.CleanupFailed), result.await())
        assertTrue(cleanup.pending)
        cleanup.hold!!.complete(Unit)
        assertEquals(AuthResult.Success, auth.signOut())
    }

    @Test fun initiatingCancellationDoesNotInterruptAlreadyRequestedLocalCleanup() = runTest {
        val raw = Auth(); val cleanup = Cleanup().apply { hold = CompletableDeferred() }
        val auth = SessionAuthRepository(raw, SessionWork(), cleanup)
        val task = launch { auth.signOut() }
        runCurrent(); task.cancel(); runCurrent()
        assertTrue(cleanup.pending)
        cleanup.hold!!.complete(Unit); task.join()
        assertEquals(1, raw.outs)
        assertFalse(cleanup.pending)
        assertEquals(AuthSession.SignedOut, auth.session.first())
    }

    @Test fun interruptedCredentialChangeRemainsBlockedDespiteLateSdkAuth() = runTest {
        val raw = Auth().apply { signInHold = CompletableDeferred() }; val cleanup = Cleanup(); val work = SessionWork()
        val auth = SessionAuthRepository(raw, work, cleanup)
        val task = launch { auth.signIn("B", "unused") }
        runCurrent(); task.cancelAndJoin()
        raw.state.value = AuthSession.Authenticated(AuthUser("B", null))
        assertTrue(cleanup.pending)
        assertEquals(AuthSession.ResolutionFailed(AuthError.CleanupFailed), auth.session.first())
        assertFailsWith<CancellationException> { work.run { error("must not run") } }
    }

    @Test fun resolutionFailureLeavesWorkClosed() = runTest {
        val raw = Auth().apply { resolutionFailure = true }; val work = SessionWork()
        val auth = SessionAuthRepository(raw, work, Cleanup())
        auth.restoreSession()
        assertEquals(AuthSession.ResolutionFailed(AuthError.SessionExpired), auth.session.first())
        assertFailsWith<CancellationException> { work.run { error("must not run") } }
    }
    @Test fun cancellationWaitsForLateListenerRegistrationToRelease() = runTest {
        val work = SessionWork(); work.open()
        val registration = CompletableDeferred<Unit>()
        var removed = false
        val flow = work.observe<Unit> {
            callbackFlow {
                withContext(NonCancellable) { registration.await() }
                awaitClose { removed = true }
            }
        }
        val collecting = launch { flow.collect() }; runCurrent()
        val close = async { work.close() }; runCurrent()
        assertFalse(close.isCompleted)
        registration.complete(Unit)
        close.await()
        assertTrue(removed)
        assertTrue(collecting.isCancelled)
    }

    @Test fun gateSignOutFailureClearsMemoryAndBusyAndDoesNotRestore() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val raw = Auth()
            val gate = com.fluxit.feature.auth.SessionGateViewModel(raw)
            store.put("gate", gate)
            runCurrent()
            assertIs<com.fluxit.feature.auth.SessionGateState.Ready>(gate.gate.value)
            var memoryCleared = false
            gate.beforeSignOut = { memoryCleared = true; assertEquals(0, raw.outs) }
            val restores = raw.restores
            raw.outFailure = true
            gate.signOutAndRetry(); runCurrent()
            assertTrue(memoryCleared)
            assertFalse(gate.isBusy.value)
            assertEquals(restores, raw.restores)
            assertEquals(com.fluxit.feature.auth.SessionGateState.ResolutionFailed(AuthError.Unknown), gate.gate.value)
            raw.outFailure = false
            gate.beforeSignOut = {}
            gate.signOut(); runCurrent()
            assertEquals(com.fluxit.feature.auth.SessionGateState.SignedOut, gate.gate.value)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun markerWriteFailureStillQuiescesAndNeverReportsSuccess() = runTest {
        val raw = Auth(); val cleanup = Cleanup().apply { failBegin = true }; val work = SessionWork()
        val auth = SessionAuthRepository(raw, work, cleanup)
        auth.restoreSession()
        val pending = launch { work.run { awaitCancellation() } }; runCurrent()
        assertEquals(AuthResult.Failure(AuthError.CleanupFailed), auth.signOut())
        assertTrue(pending.isCancelled)
        assertEquals(0, cleanup.clears)
        assertEquals(0, raw.outs)
        assertEquals(AuthSession.ResolutionFailed(AuthError.CleanupFailed), auth.session.first())
        cleanup.failBegin = false
        assertEquals(AuthResult.Success, auth.signOut())
    }

    @Test fun markerRemovalFailureRemainsRetryableAfterSdkAndAuthSuccess() = runTest {
        val raw = Auth(); val cleanup = Cleanup().apply { failComplete = true }
        val auth = SessionAuthRepository(raw, SessionWork(), cleanup)
        assertEquals(AuthResult.Failure(AuthError.CleanupFailed), auth.signOut())
        assertEquals(1, raw.outs)
        assertTrue(cleanup.pending)
        assertEquals(AuthSession.ResolutionFailed(AuthError.CleanupFailed), auth.session.first())
        cleanup.failComplete = false
        assertEquals(AuthResult.Success, auth.signOut())
        assertFalse(cleanup.pending)
    }

    @Test fun credentialMarkerRemovalFailureNeverPublishesReady() = runTest {
        val raw = Auth(); val cleanup = Cleanup(); val work = SessionWork()
        val auth = SessionAuthRepository(raw, work, cleanup)
        val states = mutableListOf<AuthSession>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { auth.session.toList(states) }
        raw.signInHold = CompletableDeferred()
        val signingIn = async { auth.signIn("B", "unused") }
        runCurrent()
        cleanup.failComplete = true
        raw.signInHold!!.complete(Unit)
        assertEquals(AuthResult.Failure(AuthError.CleanupFailed), signingIn.await())
        runCurrent()
        assertFalse(states.any { it is AuthSession.Authenticated })
        assertTrue(cleanup.pending)
        assertFailsWith<CancellationException> { work.run { error("must not run") } }
    }

    @Test fun cancellationAfterMarkerRemovalRearmsRecoveryBeforeReady() = runTest {
        val raw = Auth().apply { signInHold = CompletableDeferred() }
        val cleanup = Cleanup(); val work = SessionWork()
        val auth = SessionAuthRepository(raw, work, cleanup)
        val states = mutableListOf<AuthSession>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { auth.session.toList(states) }
        val signingIn = launch { auth.signIn("B", "unused") }
        runCurrent()
        cleanup.afterComplete = { signingIn.cancel() }
        raw.signInHold!!.complete(Unit)
        signingIn.join(); runCurrent()
        assertTrue(cleanup.pending)
        assertFalse(states.any { it is AuthSession.Authenticated })
        assertEquals(AuthSession.ResolutionFailed(AuthError.CleanupFailed), states.last())
        assertFailsWith<CancellationException> { work.run { error("must not run") } }
    }

    @Test fun startupPrivacyCleanupOutlastsRestorationBudgetWithoutFalseSignedOut() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val raw = Auth()
            val cleanup = Cleanup().apply { pending = true; hold = CompletableDeferred() }
            val work = SessionWork()
            val gate = com.fluxit.feature.auth.SessionGateViewModel(SessionAuthRepository(raw, work, cleanup))
            store.put("gate", gate)
            runCurrent()
            advanceTimeBy(10_000); runCurrent()
            assertEquals(com.fluxit.feature.auth.SessionGateState.Resolving, gate.gate.value)
            assertEquals(0, raw.restores)
            advanceTimeBy(5_000); runCurrent()
            assertEquals(com.fluxit.feature.auth.SessionGateState.ResolutionFailed(AuthError.CleanupFailed), gate.gate.value)
            assertTrue(cleanup.pending)
            assertEquals(0, raw.outs)
            assertFailsWith<CancellationException> { work.run { error("must not run") } }
            cleanup.hold!!.complete(Unit)
            gate.retryResolution(); runCurrent()
            assertEquals(com.fluxit.feature.auth.SessionGateState.SignedOut, gate.gate.value)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

}
