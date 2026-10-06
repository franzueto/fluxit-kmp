package com.fluxit.firebase.auth

import com.fluxit.domain.auth.AuthError
import com.fluxit.domain.auth.AuthSession
import com.fluxit.domain.auth.AuthUser
import com.fluxit.domain.auth.errorOrNull
import com.fluxit.domain.auth.isSuccess
import com.fluxit.domain.auth.uidOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * FB-103 unit tests for the iOS adapter's own logic: the session state machine, the
 * `callbackFlow` listener lifecycle over the Swift bridge, and error mapping. The Swift
 * bridge (and therefore the Firebase Apple SDK behind it) is replaced by
 * [RecordingAuthBridge]; the adapter code under test is the production code.
 *
 * Deliberately the same test matrix as FB-102's `AndroidAuthRepositoryTest`, so a
 * behavioural divergence between the two platforms shows up as a failing test rather
 * than as a difference nobody looks for.
 *
 * What this file does NOT prove, stated narrowly: that the real Firebase Apple SDK
 * actually reports the states and `FIRAuthErrorDomain` codes assumed here, and that the
 * Swift implementation of [IosAuthBridge] honours the protocol. That is what the
 * emulator-backed simulator check (`IosAuthIntegrationCheck`) exercises.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosAuthRepositoryTest {

    private fun repositoryFor(
        bridge: IosAuthBridge,
        diagnostics: AuthDiagnostics = RecordingDiagnostics(),
    ): IosAuthRepository = IosAuthRepository({ bridge }, diagnostics)

    private fun TestScope.collectSession(
        repository: IosAuthRepository,
        into: MutableList<AuthSession>,
    ): Job = (this as CoroutineScope).launch(UnconfinedTestDispatcher(testScheduler)) {
        repository.session.toList(into)
    }

    // --- FB-101-NB3: listener lifecycle ---------------------------------------------

    @Test
    fun collectingTheSessionRegistersExactlyOneAuthStateListener() = runTest {
        val bridge = RecordingAuthBridge()
        val repository = repositoryFor(bridge)

        val job = collectSession(repository, mutableListOf())

        assertEquals(1, bridge.addCount)
        assertEquals(1, bridge.liveListeners.size)
        assertEquals(0, bridge.removeCount)

        job.cancelAndJoin()
    }

    @Test
    fun cancellingTheCollectorRemovesTheUnderlyingAuthStateListener() = runTest {
        val bridge = RecordingAuthBridge()
        val repository = repositoryFor(bridge)

        val job = collectSession(repository, mutableListOf())
        assertEquals(1, bridge.addCount)

        job.cancelAndJoin()

        assertEquals(1, bridge.removeCount)
        assertTrue(bridge.liveListeners.isEmpty(), "the SDK listener must be released")
    }

    @Test
    fun aReleasedListenerStopsReceivingUpdatesAndDoesNotResurrectTheCollector() = runTest {
        val bridge = RecordingAuthBridge()
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<AuthSession>()

        val job = collectSession(repository, emissions)
        bridge.emitUser(AuthUser(uid = "uid-1", email = "a@example.com"))
        job.cancelAndJoin()
        val afterCancellation = emissions.size

        // A *real, subsequent* state change: if the listener had leaked, this would
        // still be delivered and the emission list would grow.
        bridge.emitUser(AuthUser(uid = "uid-2", email = "b@example.com"))

        assertEquals(afterCancellation, emissions.size)
        assertFalse(
            emissions.any { it.uidOrNull == "uid-2" },
            "no post-cancellation state may reach a released collector",
        )
        assertEquals(1, bridge.addCount)
        assertEquals(1, bridge.removeCount)
    }

    @Test
    fun eachCollectorOwnsItsOwnListenerAndReleasesIt() = runTest {
        val bridge = RecordingAuthBridge()
        val repository = repositoryFor(bridge)

        val first = collectSession(repository, mutableListOf())
        val second = collectSession(repository, mutableListOf())
        assertEquals(2, bridge.addCount)
        assertEquals(2, bridge.liveListeners.size)

        first.cancelAndJoin()
        assertEquals(1, bridge.removeCount)
        assertEquals(1, bridge.liveListeners.size)

        second.cancelAndJoin()
        assertEquals(2, bridge.removeCount)
        assertTrue(bridge.liveListeners.isEmpty())
    }

    // --- session states ---------------------------------------------------------------

    @Test
    fun sessionStartsUnresolvedBeforeTheBridgeReports() = runTest {
        val bridge = RecordingAuthBridge()
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<AuthSession>()

        val job = collectSession(repository, emissions)

        assertEquals(AuthSession.Unresolved, emissions.first())
        job.cancelAndJoin()
    }

    @Test
    fun aListenerCallbackWithNoUserResolvesSignedOutNotUnresolved() = runTest {
        val bridge = RecordingAuthBridge()
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<AuthSession>()

        val job = collectSession(repository, emissions)
        bridge.emitUser(null)

        assertEquals(AuthSession.Unresolved, emissions.first())
        assertEquals(AuthSession.SignedOut, emissions.last())
        job.cancelAndJoin()
    }

    @Test
    fun aListenerCallbackWithAUserResolvesAuthenticatedWithTheUid() = runTest {
        val bridge = RecordingAuthBridge()
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<AuthSession>()

        val job = collectSession(repository, emissions)
        bridge.emitUser(AuthUser(uid = "uid-7", email = "a@example.com", isEmailVerified = true))

        assertEquals("uid-7", emissions.last().uidOrNull)
        job.cancelAndJoin()
    }

    // --- restoration ------------------------------------------------------------------

    @Test
    fun restoreWithoutAPersistedUserResolvesSignedOut() = runTest {
        val bridge = RecordingAuthBridge(user = null)
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<AuthSession>()

        val job = collectSession(repository, emissions)
        repository.restoreSession()

        assertEquals(AuthSession.SignedOut, emissions.last())
        job.cancelAndJoin()
    }

    @Test
    fun restoreWithAPersistedUserRevalidatesItAndResolvesAuthenticated() = runTest {
        val bridge = RecordingAuthBridge(user = AuthUser(uid = "uid-9", email = "a@example.com"))
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<AuthSession>()

        val job = collectSession(repository, emissions)
        repository.restoreSession()

        assertEquals("uid-9", emissions.last().uidOrNull)
        job.cancelAndJoin()
    }

    @Test
    fun restoreFailureIsResolutionFailedAndRetryStillSucceeds() = runTest {
        val bridge = RecordingAuthBridge(user = AuthUser(uid = "uid-9", email = "a@example.com"))
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<AuthSession>()

        val job = collectSession(repository, emissions)
        bridge.reloadFailure = authError(17021L) // userTokenExpired
        repository.restoreSession()

        val failed = assertIs<AuthSession.ResolutionFailed>(emissions.last())
        assertEquals(AuthError.SessionExpired, failed.error)

        bridge.reloadFailure = null
        repository.restoreSession()
        assertEquals("uid-9", emissions.last().uidOrNull)

        job.cancelAndJoin()
    }

    // --- operations -------------------------------------------------------------------

    @Test
    fun signUpAuthenticatesTheNewAccount() = runTest {
        val bridge = RecordingAuthBridge()
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<AuthSession>()

        val job = collectSession(repository, emissions)
        val result = repository.signUp("new@example.com", "sw0rdfish")

        assertTrue(result.isSuccess)
        assertEquals("uid-new@example.com", emissions.last().uidOrNull)
        job.cancelAndJoin()
    }

    @Test
    fun signInAuthenticatesAndSignOutResolvesSignedOut() = runTest {
        val bridge = RecordingAuthBridge()
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<AuthSession>()

        val job = collectSession(repository, emissions)
        assertTrue(repository.signIn("a@example.com", "sw0rdfish").isSuccess)
        assertEquals("uid-a@example.com", emissions.last().uidOrNull)

        assertTrue(repository.signOut().isSuccess)
        assertEquals(AuthSession.SignedOut, emissions.last())
        assertEquals(1, bridge.signOutCount)

        job.cancelAndJoin()
    }

    @Test
    fun aFailedSignInReportsTheErrorAndLeavesTheSessionUntouched() = runTest {
        val bridge = RecordingAuthBridge()
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<AuthSession>()

        val job = collectSession(repository, emissions)
        bridge.emitUser(null)
        val emissionsBefore = emissions.size

        bridge.signInFailure = authError(17009L) // wrongPassword
        val result = repository.signIn("a@example.com", "wrong")

        assertEquals(AuthError.InvalidCredentials, result.errorOrNull)
        assertEquals(AuthSession.SignedOut, emissions.last())
        assertEquals(emissionsBefore, emissions.size, "a failed operation must not move the session")

        job.cancelAndJoin()
    }

    @Test
    fun passwordRecoveryIsSentAndDoesNotTouchTheSession() = runTest {
        val bridge = RecordingAuthBridge()
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<AuthSession>()

        val job = collectSession(repository, emissions)
        bridge.emitUser(AuthUser(uid = "uid-1", email = "a@example.com"))
        val emissionsBefore = emissions.size

        assertTrue(repository.sendPasswordResetEmail("a@example.com").isSuccess)

        assertEquals(listOf("a@example.com"), bridge.passwordResetsSent)
        assertEquals(emissionsBefore, emissions.size)
        assertEquals("uid-1", emissions.last().uidOrNull)

        job.cancelAndJoin()
    }

    @Test
    fun aFailedPasswordRecoveryReportsFailureWithoutChangingTheSession() = runTest {
        val bridge = RecordingAuthBridge()
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<AuthSession>()

        val job = collectSession(repository, emissions)
        bridge.emitUser(null)
        val emissionsBefore = emissions.size

        bridge.passwordResetFailure = authError(17011L) // userNotFound
        val result = repository.sendPasswordResetEmail("nobody@example.com")

        assertEquals(AuthError.UserNotFound, result.errorOrNull)
        assertEquals(emissionsBefore, emissions.size)

        job.cancelAndJoin()
    }

    @Test
    fun aFailedSignOutReportsFailureAndDoesNotClaimSignedOut() = runTest {
        val bridge = RecordingAuthBridge()
        val repository = repositoryFor(bridge)
        val emissions = mutableListOf<AuthSession>()

        val job = collectSession(repository, emissions)
        bridge.emitUser(AuthUser(uid = "uid-1", email = "a@example.com"))

        bridge.signOutFailure = authError(17995L) // keychainError
        val result = repository.signOut()

        assertEquals(AuthError.Unknown, result.errorOrNull)
        assertEquals("uid-1", emissions.last().uidOrNull)

        job.cancelAndJoin()
    }

    // --- FB-101-NB2: diagnostics before collapsing into Unknown -----------------------

    @Test
    fun anUnmappedSdkFailureIsLoggedBeforeItBecomesUnknown() = runTest {
        val diagnostics = RecordingDiagnostics()
        val bridge = RecordingAuthBridge()
        val repository = repositoryFor(bridge, diagnostics)

        bridge.signInFailure = authError(17999L) // internalError, deliberately unmapped
        val result = repository.signIn("a@example.com", "sw0rdfish")

        assertEquals(AuthError.Unknown, result.errorOrNull)
        assertEquals(1, diagnostics.entries.size)
        val entry = diagnostics.entries.single()
        assertEquals("signIn", entry.operation)
        assertEquals(FIREBASE_AUTH_ERROR_DOMAIN, entry.domain)
        assertEquals(17999L, entry.code, "the original SDK code must survive in the log")
        assertFalse(entry.recognised)
    }

    @Test
    fun aMappedSdkFailureIsAlsoReportedToDiagnostics() = runTest {
        val diagnostics = RecordingDiagnostics()
        val bridge = RecordingAuthBridge()
        val repository = repositoryFor(bridge, diagnostics)

        bridge.signUpFailure = authError(17007L) // emailAlreadyInUse
        val result = repository.signUp("taken@example.com", "sw0rdfish")

        assertEquals(AuthError.EmailAlreadyInUse, result.errorOrNull)
        val entry = diagnostics.entries.single()
        assertEquals("signUp", entry.operation)
        assertTrue(entry.recognised)
    }

    // --- bridge robustness --------------------------------------------------------------

    @Test
    fun aBridgeThatCallsBackTwiceDoesNotBreakTheAdapter() = runTest {
        val bridge = RecordingAuthBridge()
        bridge.invokeCompletionsTwice = true
        val repository = repositoryFor(bridge)

        // A double completion from Swift must not crash the coroutine machinery.
        assertTrue(repository.signIn("a@example.com", "sw0rdfish").isSuccess)
        assertTrue(repository.signOut().isSuccess)
    }
}
