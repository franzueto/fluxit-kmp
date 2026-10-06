package com.fluxit

import com.fluxit.domain.auth.AuthError
import com.fluxit.domain.auth.AuthResult
import com.fluxit.domain.auth.AuthSession
import com.fluxit.domain.auth.AuthUser
import com.fluxit.domain.auth.errorOrNull
import com.fluxit.domain.auth.isResolved
import com.fluxit.domain.auth.isSuccess
import com.fluxit.domain.auth.uidOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AuthSessionStateTest {

    @Test
    fun unresolvedIsNotResolvedAndCarriesNoUid() {
        val session: AuthSession = AuthSession.Unresolved
        assertFalse(session.isResolved)
        assertNull(session.uidOrNull)
    }

    @Test
    fun signedOutIsResolvedAndDistinctFromUnresolved() {
        val signedOut: AuthSession = AuthSession.SignedOut
        val unresolved: AuthSession = AuthSession.Unresolved
        assertTrue(signedOut.isResolved)
        assertNull(signedOut.uidOrNull)
        assertNotEquals(unresolved, signedOut)
    }

    @Test
    fun authenticatedCarriesUid() {
        val session: AuthSession = AuthSession.Authenticated(AuthUser("uid-42", "a@b.com"))
        assertTrue(session.isResolved)
        assertEquals("uid-42", session.uidOrNull)
    }

    @Test
    fun resolutionFailedIsResolvedButCarriesNoUid() {
        val failed = AuthSession.ResolutionFailed(AuthError.NetworkUnavailable)
        val session: AuthSession = failed
        assertTrue(session.isResolved)
        assertNull(session.uidOrNull)
        assertEquals(AuthError.NetworkUnavailable, failed.error)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryRestorationTest {

    @Test
    fun sessionStartsUnresolvedBeforeRestoration() = runTest {
        val repo = FakeAuthRepository(initialAccounts = mapOf("a@b.com" to "secret1"))
        assertEquals(AuthSession.Unresolved, repo.session.first())
    }

    @Test
    fun restoreWithoutPersistedUserResolvesSignedOut() = runTest {
        val repo = FakeAuthRepository(initialAccounts = mapOf("a@b.com" to "secret1"))

        repo.restoreSession()

        assertEquals(AuthSession.SignedOut, repo.session.first())
    }

    @Test
    fun restoreWithPersistedUserResolvesAuthenticatedWithUid() = runTest {
        val repo = FakeAuthRepository(
            initialAccounts = mapOf("a@b.com" to "secret1"),
            persistedAccountEmail = "a@b.com",
        )

        repo.restoreSession()

        val session = assertIs<AuthSession.Authenticated>(repo.session.first())
        assertEquals("a@b.com", session.user.email)
        assertTrue(session.user.uid.isNotBlank())
    }

    @Test
    fun restoreFailureIsResolutionFailedAndRetryStillSucceeds() = runTest {
        val repo = FakeAuthRepository(
            initialAccounts = mapOf("a@b.com" to "secret1"),
            persistedAccountEmail = "a@b.com",
        )
        repo.nextRestoreFailure = AuthError.NetworkUnavailable

        repo.restoreSession()
        val failed = assertIs<AuthSession.ResolutionFailed>(repo.session.first())
        assertEquals(AuthError.NetworkUnavailable, failed.error)

        repo.restoreSession()
        assertEquals("uid-0", repo.session.first().uidOrNull)
    }

    @Test
    fun restorationTransitionSequenceIsObservable() = runTest {
        val repo = FakeAuthRepository(
            initialAccounts = mapOf("a@b.com" to "secret1"),
            persistedAccountEmail = "a@b.com",
        )
        val observed = mutableListOf<AuthSession>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.session.collect { observed += it }
        }

        repo.nextRestoreFailure = AuthError.NetworkUnavailable
        repo.restoreSession()
        repo.restoreSession()
        repo.signOut()

        job.cancel()
        assertEquals(
            listOf(
                AuthSession.Unresolved,
                AuthSession.ResolutionFailed(AuthError.NetworkUnavailable),
                AuthSession.Authenticated(AuthUser("uid-0", "a@b.com")),
                AuthSession.SignedOut,
            ),
            observed,
        )
    }

    @Test
    fun cancellingCollectionDoesNotStopTheRepository() = runTest {
        val repo = FakeAuthRepository(initialAccounts = mapOf("a@b.com" to "secret1"))
        val observed = mutableListOf<AuthSession>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.session.collect { observed += it }
        }
        job.cancel()

        repo.restoreSession()

        assertEquals(listOf<AuthSession>(AuthSession.Unresolved), observed)
        assertEquals(AuthSession.SignedOut, repo.session.first())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryFlowsTest {

    private fun repo(vararg accounts: Pair<String, String>) =
        FakeAuthRepository(initialAccounts = accounts.toMap())

    @Test
    fun signUpAuthenticatesTheNewAccount() = runTest {
        val repo = repo()
        repo.restoreSession()

        val result = repo.signUp("new@b.com", "secret1")

        assertTrue(result.isSuccess)
        val session = assertIs<AuthSession.Authenticated>(repo.session.first())
        assertEquals("new@b.com", session.user.email)
    }

    @Test
    fun signUpWithExistingEmailFailsAndLeavesSessionSignedOut() = runTest {
        val repo = repo("a@b.com" to "secret1")
        repo.restoreSession()

        val result = repo.signUp("a@b.com", "secret2")

        assertEquals(AuthError.EmailAlreadyInUse, result.errorOrNull)
        assertEquals(AuthSession.SignedOut, repo.session.first())
    }

    @Test
    fun signUpWithWeakPasswordFails() = runTest {
        val repo = repo()
        repo.restoreSession()

        assertEquals(AuthError.WeakPassword, repo.signUp("new@b.com", "12345").errorOrNull)
        assertEquals(AuthSession.SignedOut, repo.session.first())
    }

    @Test
    fun signUpWithInvalidEmailFails() = runTest {
        val repo = repo()
        repo.restoreSession()

        assertEquals(AuthError.InvalidEmail, repo.signUp("not-an-email", "secret1").errorOrNull)
    }

    @Test
    fun signInWithCorrectCredentialsAuthenticates() = runTest {
        val repo = repo("a@b.com" to "secret1")
        repo.restoreSession()

        assertTrue(repo.signIn("a@b.com", "secret1").isSuccess)
        assertEquals("uid-0", repo.session.first().uidOrNull)
    }

    @Test
    fun signInWithWrongPasswordReportsInvalidCredentialsWithoutChangingSession() = runTest {
        val repo = repo("a@b.com" to "secret1")
        repo.restoreSession()

        val result = repo.signIn("a@b.com", "wrong-password")

        assertEquals(AuthError.InvalidCredentials, result.errorOrNull)
        assertEquals(AuthSession.SignedOut, repo.session.first())
    }

    @Test
    fun signInWithUnknownEmailReportsUserNotFound() = runTest {
        val repo = repo("a@b.com" to "secret1")
        repo.restoreSession()

        assertEquals(AuthError.UserNotFound, repo.signIn("ghost@b.com", "secret1").errorOrNull)
    }

    @Test
    fun networkFailureDuringSignInIsAnOperationErrorNotAResolutionFailure() = runTest {
        val repo = repo("a@b.com" to "secret1")
        repo.restoreSession()
        repo.nextOperationFailure = AuthError.NetworkUnavailable

        val result = repo.signIn("a@b.com", "secret1")

        assertEquals(AuthError.NetworkUnavailable, result.errorOrNull)
        assertEquals(AuthSession.SignedOut, repo.session.first())
    }

    @Test
    fun signOutResolvesSignedOutAndDropsTheUid() = runTest {
        val repo = repo("a@b.com" to "secret1")
        repo.restoreSession()
        repo.signIn("a@b.com", "secret1")

        val result = repo.signOut()

        assertTrue(result.isSuccess)
        assertEquals(1, repo.signOutCount)
        assertEquals(AuthSession.SignedOut, repo.session.first())
        assertNull(repo.session.first().uidOrNull)
    }

    @Test
    fun signingInAsASecondUserReplacesTheUid() = runTest {
        val repo = repo("a@b.com" to "secret1", "c@d.com" to "secret2")
        repo.restoreSession()
        repo.signIn("a@b.com", "secret1")
        val first = repo.session.first().uidOrNull
        repo.signOut()

        repo.signIn("c@d.com", "secret2")

        val second = repo.session.first().uidOrNull
        assertTrue(first != null && second != null && first != second)
    }

    @Test
    fun passwordRecoveryIsSentForAKnownAccount() = runTest {
        val repo = repo("a@b.com" to "secret1")
        repo.restoreSession()

        assertTrue(repo.sendPasswordResetEmail("a@b.com").isSuccess)
        assertEquals(listOf("a@b.com"), repo.passwordResetsSent)
        assertEquals(AuthSession.SignedOut, repo.session.first())
    }

    @Test
    fun passwordRecoveryForUnknownAccountReportsUserNotFound() = runTest {
        val repo = repo("a@b.com" to "secret1")
        repo.restoreSession()

        assertEquals(AuthError.UserNotFound, repo.sendPasswordResetEmail("ghost@b.com").errorOrNull)
        assertTrue(repo.passwordResetsSent.isEmpty())
    }

    @Test
    fun failureCarriesTheTypedErrorAndSuccessCarriesNone() = runTest {
        val repo = repo("a@b.com" to "secret1")
        repo.restoreSession()

        assertNull(repo.signIn("a@b.com", "secret1").errorOrNull)
        assertFalse(AuthResult.Failure(AuthError.Unknown).isSuccess)
    }
}
