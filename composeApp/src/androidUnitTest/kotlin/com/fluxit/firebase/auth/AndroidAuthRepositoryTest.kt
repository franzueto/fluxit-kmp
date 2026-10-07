package com.fluxit.firebase.auth

import com.fluxit.domain.auth.AuthError
import com.fluxit.domain.auth.AuthResult
import com.fluxit.domain.auth.AuthSession
import com.fluxit.domain.auth.AuthUser
import com.fluxit.domain.auth.errorOrNull
import com.fluxit.domain.auth.isSuccess
import com.fluxit.domain.auth.uidOrNull
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
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
 * unit tests for the Android adapter's own logic: the session state machine, the
 * `callbackFlow` listener lifecycle, and error mapping. The Firebase SDK is replaced by
 * [RecordingAuthGateway]; the adapter code under test is the production code.
 *
 * What this file does NOT prove is deliberately narrow and is covered instead by
 * `FirebaseAuthIntegrationTest` (instrumented, real SDK against the Auth emulator):
 * that the real SDK actually reports the states/codes assumed here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidAuthRepositoryTest {

    private fun TestScope.collectSession(
        repository: AndroidAuthRepository,
        into: MutableList<AuthSession>,
    ): Job = (this as CoroutineScope).launch(UnconfinedTestDispatcher(testScheduler)) {
        repository.session.toList(into)
    }

    // --- Listener lifecycle -------------------------------------------

    @Test
    fun collectingTheSessionRegistersExactlyOneAuthStateListener() = runTest {
        val gateway = RecordingAuthGateway()
        val repository = AndroidAuthRepository(gateway, RecordingDiagnostics())
        val emissions = mutableListOf<AuthSession>()

        val job = collectSession(repository, emissions)

        assertEquals(1, gateway.addCount)
        assertEquals(1, gateway.liveListeners.size)
        assertEquals(0, gateway.removeCount)

        job.cancelAndJoin()
    }

    @Test
    fun cancellingTheCollectorRemovesTheUnderlyingAuthStateListener() = runTest {
        val gateway = RecordingAuthGateway()
        val repository = AndroidAuthRepository(gateway, RecordingDiagnostics())
        val emissions = mutableListOf<AuthSession>()

        val job = collectSession(repository, emissions)
        assertEquals(1, gateway.addCount)

        job.cancelAndJoin()

        assertEquals(1, gateway.removeCount)
        assertTrue(gateway.liveListeners.isEmpty(), "the SDK listener must be released")
    }

    @Test
    fun aReleasedListenerStopsReceivingUpdatesAndDoesNotResurrectTheCollector() = runTest {
        val gateway = RecordingAuthGateway()
        val repository = AndroidAuthRepository(gateway, RecordingDiagnostics())
        val emissions = mutableListOf<AuthSession>()

        val job = collectSession(repository, emissions)
        gateway.emitUser(AuthUser(uid = "uid-1", email = "a@example.com"))
        job.cancelAndJoin()
        val afterCancellation = emissions.size

        gateway.emitUser(AuthUser(uid = "uid-2", email = "b@example.com"))

        assertEquals(afterCancellation, emissions.size)
        assertEquals(1, gateway.addCount)
        assertEquals(1, gateway.removeCount)
    }

    @Test
    fun eachCollectorOwnsItsOwnListenerAndReleasesIt() = runTest {
        val gateway = RecordingAuthGateway()
        val repository = AndroidAuthRepository(gateway, RecordingDiagnostics())

        val first = collectSession(repository, mutableListOf())
        val second = collectSession(repository, mutableListOf())
        assertEquals(2, gateway.addCount)
        assertEquals(2, gateway.liveListeners.size)

        first.cancelAndJoin()
        assertEquals(1, gateway.removeCount)
        assertEquals(1, gateway.liveListeners.size)

        second.cancelAndJoin()
        assertEquals(2, gateway.removeCount)
        assertTrue(gateway.liveListeners.isEmpty())
    }

    // --- session states -------------------------------------------------------------

    @Test
    fun sessionStartsUnresolvedBeforeTheProviderReports() = runTest {
        val gateway = RecordingAuthGateway()
        val repository = AndroidAuthRepository(gateway, RecordingDiagnostics())
        val emissions = mutableListOf<AuthSession>()

        val job = collectSession(repository, emissions)

        assertEquals(listOf<AuthSession>(AuthSession.Unresolved), emissions)
        assertEquals(null, emissions.first().uidOrNull)

        job.cancelAndJoin()
    }

    @Test
    fun aListenerCallbackWithNoUserResolvesSignedOutNotUnresolved() = runTest {
        val gateway = RecordingAuthGateway()
        val repository = AndroidAuthRepository(gateway, RecordingDiagnostics())
        val emissions = mutableListOf<AuthSession>()
        val job = collectSession(repository, emissions)

        gateway.emitUser(null)

        assertEquals(
            listOf(AuthSession.Unresolved, AuthSession.SignedOut),
            emissions,
        )
        job.cancelAndJoin()
    }

    @Test
    fun aListenerCallbackWithAUserResolvesAuthenticatedWithTheUid() = runTest {
        val gateway = RecordingAuthGateway()
        val repository = AndroidAuthRepository(gateway, RecordingDiagnostics())
        val emissions = mutableListOf<AuthSession>()
        val job = collectSession(repository, emissions)

        gateway.emitUser(AuthUser(uid = "uid-42", email = "a@example.com", isEmailVerified = true))

        assertEquals("uid-42", emissions.last().uidOrNull)
        assertIs<AuthSession.Authenticated>(emissions.last())
        job.cancelAndJoin()
    }

    // --- restoration ------------------------------------------------------------------

    @Test
    fun restoreWithoutAPersistedUserResolvesSignedOut() = runTest {
        val gateway = RecordingAuthGateway(user = null)
        val repository = AndroidAuthRepository(gateway, RecordingDiagnostics())
        val emissions = mutableListOf<AuthSession>()
        val job = collectSession(repository, emissions)

        repository.restoreSession()

        assertEquals(AuthSession.SignedOut, emissions.last())
        job.cancelAndJoin()
    }

    @Test
    fun restoreWithAPersistedUserRevalidatesItAndResolvesAuthenticated() = runTest {
        val persisted = AuthUser(uid = "uid-persisted", email = "a@example.com")
        val gateway = RecordingAuthGateway(user = persisted)
        val repository = AndroidAuthRepository(gateway, RecordingDiagnostics())
        val emissions = mutableListOf<AuthSession>()
        val job = collectSession(repository, emissions)

        repository.restoreSession()

        assertEquals(AuthSession.Authenticated(persisted), emissions.last())
        job.cancelAndJoin()
    }

    @Test
    fun restoreFailureIsResolutionFailedAndRetryStillSucceeds() = runTest {
        val persisted = AuthUser(uid = "uid-persisted", email = "a@example.com")
        val gateway = RecordingAuthGateway(user = persisted)
        val diagnostics = RecordingDiagnostics()
        val repository = AndroidAuthRepository(gateway, diagnostics)
        val emissions = mutableListOf<AuthSession>()
        val job = collectSession(repository, emissions)

        gateway.reloadFailure = IOException("transport down")
        repository.restoreSession()

        assertEquals(
            AuthSession.ResolutionFailed(AuthError.NetworkUnavailable),
            emissions.last(),
        )
        assertEquals("restoreSession", diagnostics.entries.single().operation)

        gateway.reloadFailure = null
        repository.restoreSession()

        assertEquals(AuthSession.Authenticated(persisted), emissions.last())
        job.cancelAndJoin()
    }

    // --- interactive operations ---------------------------------------------------------

    @Test
    fun signUpAuthenticatesTheNewAccount() = runTest {
        val gateway = RecordingAuthGateway()
        val repository = AndroidAuthRepository(gateway, RecordingDiagnostics())
        val emissions = mutableListOf<AuthSession>()
        val job = collectSession(repository, emissions)

        val result = repository.signUp("new@example.com", "secret1")

        assertTrue(result.isSuccess)
        assertEquals("uid-new@example.com", emissions.last().uidOrNull)
        job.cancelAndJoin()
    }

    @Test
    fun signInAuthenticatesAndSignOutResolvesSignedOut() = runTest {
        val gateway = RecordingAuthGateway()
        val repository = AndroidAuthRepository(gateway, RecordingDiagnostics())
        val emissions = mutableListOf<AuthSession>()
        val job = collectSession(repository, emissions)

        assertTrue(repository.signIn("a@example.com", "secret1").isSuccess)
        assertEquals("uid-a@example.com", emissions.last().uidOrNull)

        assertTrue(repository.signOut().isSuccess)

        assertEquals(AuthSession.SignedOut, emissions.last())
        assertEquals(1, gateway.signOutCount)
        job.cancelAndJoin()
    }

    @Test
    fun aFailedSignInReportsTheErrorAndLeavesTheSessionUntouched() = runTest {
        val gateway = RecordingAuthGateway()
        val repository = AndroidAuthRepository(gateway, RecordingDiagnostics())
        val emissions = mutableListOf<AuthSession>()
        val job = collectSession(repository, emissions)
        gateway.emitUser(null)

        gateway.signInFailure = IOException("transport down")
        val result = repository.signIn("a@example.com", "wrong")

        assertEquals(AuthError.NetworkUnavailable, result.errorOrNull)
        assertEquals(AuthSession.SignedOut, emissions.last())
        job.cancelAndJoin()
    }

    @Test
    fun passwordRecoveryIsSentAndDoesNotTouchTheSession() = runTest {
        val gateway = RecordingAuthGateway()
        val repository = AndroidAuthRepository(gateway, RecordingDiagnostics())
        val emissions = mutableListOf<AuthSession>()
        val job = collectSession(repository, emissions)

        val result = repository.sendPasswordResetEmail("a@example.com")

        assertTrue(result.isSuccess)
        assertEquals(listOf("a@example.com"), gateway.passwordResetsSent)
        assertEquals(listOf<AuthSession>(AuthSession.Unresolved), emissions)
        job.cancelAndJoin()
    }

    @Test
    fun aFailedPasswordRecoveryReportsFailureWithoutChangingTheSession() = runTest {
        val gateway = RecordingAuthGateway()
        val repository = AndroidAuthRepository(gateway, RecordingDiagnostics())
        val emissions = mutableListOf<AuthSession>()
        val job = collectSession(repository, emissions)
        gateway.emitUser(null)

        gateway.passwordResetFailure = IllegalStateException("provider exploded")
        val result = repository.sendPasswordResetEmail("a@example.com")

        assertIs<AuthResult.Failure>(result)
        assertEquals(AuthError.Unknown, result.error)
        assertEquals(AuthSession.SignedOut, emissions.last())
        job.cancelAndJoin()
    }

    // --- Diagnostics before collapsing into Unknown --------------------------

    @Test
    fun anUnmappedSdkFailureIsLoggedBeforeItBecomesUnknown() = runTest {
        val gateway = RecordingAuthGateway()
        val diagnostics = RecordingDiagnostics()
        val repository = AndroidAuthRepository(gateway, diagnostics)
        val original = IllegalStateException("something the taxonomy does not model")

        gateway.signUpFailure = original
        val result = repository.signUp("a@example.com", "secret1")

        assertEquals(AuthError.Unknown, result.errorOrNull)
        val logged = diagnostics.entries.single()
        assertSame(original, logged.throwable, "the original SDK throwable must be logged")
        assertEquals("signUp", logged.operation)
        assertFalse(logged.recognised, "an unmapped failure must be reported as unmapped")
        assertEquals(AuthError.Unknown, logged.mapped)
    }

    @Test
    fun aMappedSdkFailureIsAlsoReportedToDiagnostics() = runTest {
        val gateway = RecordingAuthGateway()
        val diagnostics = RecordingDiagnostics()
        val repository = AndroidAuthRepository(gateway, diagnostics)

        gateway.signInFailure = IOException("offline")
        repository.signIn("a@example.com", "secret1")

        val logged = diagnostics.entries.single()
        assertTrue(logged.recognised)
        assertEquals(AuthError.NetworkUnavailable, logged.mapped)
    }
}
