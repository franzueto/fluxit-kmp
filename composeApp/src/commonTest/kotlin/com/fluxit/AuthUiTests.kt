package com.fluxit

import com.fluxit.domain.auth.AuthError
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthResult
import com.fluxit.domain.auth.AuthSession
import com.fluxit.domain.auth.AuthUser
import com.fluxit.feature.auth.AuthFormError
import com.fluxit.feature.auth.AuthMode
import com.fluxit.feature.auth.AuthViewModel
import com.fluxit.feature.auth.SessionGateState
import com.fluxit.feature.auth.SessionGateViewModel
import com.fluxit.feature.auth.allowsUserScopedWork
import com.fluxit.feature.auth.toGateState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * unit coverage for the root session gate.
 *
 * The acceptance criterion ("no user-scoped listener starts before session resolution")
 * is ultimately structural - `AppNavHost` is composed only from the gate's `Ready`
 * branch - and is proven end to end by the two-platform manual matrix. What these tests
 * pin down is the part that *can* be machine-checked: that the gate never reports
 * `allowsUserScopedWork` before resolution, never collapses `Unresolved` into
 * `SignedOut`, and offers a recovery path that actually works for the failure mode
 * describes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionGateViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun gateStartsResolvingAndForbidsUserScopedWorkBeforeRestorationCompletes() = runTest(dispatcher) {
        val vm = SessionGateViewModel(FakeAuthRepository(persistedAccountEmail = "a@b.com"))

        // Nothing has been allowed to run yet: this is the exact window in which a
        // user-scoped listener must not exist.
        assertEquals(SessionGateState.Resolving, vm.gate.value)
        assertFalse(vm.gate.value.allowsUserScopedWork)

        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun restorationWithNoPersistedUserResolvesToSignedOutNotReady() = runTest(dispatcher) {
        val vm = SessionGateViewModel(FakeAuthRepository())

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(SessionGateState.SignedOut, vm.gate.value)
        assertFalse(vm.gate.value.allowsUserScopedWork)
    }

    @Test
    fun restorationWithAPersistedUserResolvesToReadyWithThatUid() = runTest(dispatcher) {
        val repository = FakeAuthRepository(
            initialAccounts = mapOf("a@b.com" to "secret1"),
            persistedAccountEmail = "a@b.com",
        )
        val vm = SessionGateViewModel(repository)

        dispatcher.scheduler.advanceUntilIdle()

        val state = assertIs<SessionGateState.Ready>(vm.gate.value)
        assertEquals("a@b.com", state.user.email)
        assertTrue(state.user.uid.isNotBlank())
        assertTrue(vm.gate.value.allowsUserScopedWork)
    }

    @Test
    fun readyIsNeverObservedBeforeTheResolvingState() = runTest(dispatcher) {
        val repository = FakeAuthRepository(
            initialAccounts = mapOf("a@b.com" to "secret1"),
            persistedAccountEmail = "a@b.com",
        )
        val observed = mutableListOf<SessionGateState>()
        val vm = SessionGateViewModel(repository)
        val job = launch { vm.gate.collect { observed += it } }

        dispatcher.scheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(SessionGateState.Resolving, observed.first())
        assertIs<SessionGateState.Ready>(observed.last())
        // No state before the first Ready may permit user-scoped work.
        val firstReadyIndex = observed.indexOfFirst { it.allowsUserScopedWork }
        assertTrue(firstReadyIndex > 0)
        assertTrue(observed.take(firstReadyIndex).none { it.allowsUserScopedWork })
    }

    @Test
    fun aResolutionFailureSurfacesTheErrorAndStillForbidsUserScopedWork() = runTest(dispatcher) {
        val repository = FakeAuthRepository().apply {
            nextRestoreFailure = AuthError.NetworkUnavailable
        }
        val vm = SessionGateViewModel(repository)

        dispatcher.scheduler.advanceUntilIdle()

        val state = assertIs<SessionGateState.ResolutionFailed>(vm.gate.value)
        assertEquals(AuthError.NetworkUnavailable, state.error)
        assertFalse(vm.gate.value.allowsUserScopedWork)
    }

    @Test
    fun aTransientResolutionFailureRecoversWithAPlainRetry() = runTest(dispatcher) {
        val repository = FakeAuthRepository(
            initialAccounts = mapOf("a@b.com" to "secret1"),
            persistedAccountEmail = "a@b.com",
        ).apply { nextRestoreFailure = AuthError.NetworkUnavailable }
        val vm = SessionGateViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()
        assertIs<SessionGateState.ResolutionFailed>(vm.gate.value)

        vm.retryResolution()
        dispatcher.scheduler.advanceUntilIdle()

        assertIs<SessionGateState.Ready>(vm.gate.value)
    }

    /**
     * The case, and the reason the failure screen has two buttons.
     *
     * [StuckSessionAuthRepository] reproduces what both real adapters do: a revoked
     * credential fails resolution, and `restoreSession()` does **not** sign out, so the
     * failure is permanent under a bare retry. Only the explicit sign-out-and-retry
     * action clears the credential and returns the gate to SignedOut.
     */
    @Test
    fun aBareRetryCannotEscapeAHardResolutionFailureButSignOutAndRetryCan() = runTest(dispatcher) {
        val repository = StuckSessionAuthRepository()
        val vm = SessionGateViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            SessionGateState.ResolutionFailed(AuthError.SessionExpired),
            vm.gate.value,
        )

        repeat(3) {
            vm.retryResolution()
            dispatcher.scheduler.advanceUntilIdle()
        }
        assertEquals(
            SessionGateState.ResolutionFailed(AuthError.SessionExpired),
            vm.gate.value,
        )
        assertEquals(0, repository.signOutCount)
        assertFalse(vm.gate.value.allowsUserScopedWork)

        vm.signOutAndRetry()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(SessionGateState.SignedOut, vm.gate.value)
        assertEquals(1, repository.signOutCount)
    }

    /**
     * regression test. Fails against the pre-fix gate.
     *
     * [OptimisticRestoreAuthRepository] reproduces what both real adapters do on a cold
     * start with a persisted credential: collecting `session` registers an SDK auth-state
     * listener that immediately reports the locally *cached* credential, while
     * `restoreSession()` is still round-tripping to the server to find out whether that
     * credential is actually still valid. Here the server says it is not.
     *
     * The pre-fix gate mirrored the raw flow, so it published Ready - unlocking
     * user-scoped work - on the cached credential and only reverted to ResolutionFailed
     * afterwards. The fixed gate must never report Ready at all in this scenario.
     */
    @Test
    fun readyIsNeverReportedOnACachedCredentialThatTheServerLaterRejects() = runTest(dispatcher) {
        val repository = OptimisticRestoreAuthRepository(
            outcome = AuthSession.ResolutionFailed(AuthError.SessionExpired),
        )
        val observed = mutableListOf<SessionGateState>()
        val vm = SessionGateViewModel(repository)
        val job = launch { vm.gate.collect { observed += it } }

        // Let the cached-credential emission land, but not the server round trip.
        dispatcher.scheduler.advanceTimeBy(OptimisticRestoreAuthRepository.RESTORE_MILLIS / 2)
        dispatcher.scheduler.runCurrent()
        assertFalse(repository.restoreSessionReturned)
        assertEquals(SessionGateState.Resolving, vm.gate.value)

        dispatcher.scheduler.advanceUntilIdle()
        job.cancel()

        assertTrue(repository.restoreSessionReturned)
        assertEquals(
            SessionGateState.ResolutionFailed(AuthError.SessionExpired),
            vm.gate.value,
        )
        // The load-bearing assertion: no observed state ever unlocked user-scoped work.
        assertTrue(
            observed.none { it.allowsUserScopedWork },
            "gate must never report Ready for an unvalidated cached credential, saw $observed",
        )
    }

    /**
     * The same race, but the server confirms the cached credential. Ready is correct
     * here - but only *after* restoration completes, never before it.
     */
    @Test
    fun readyIsWithheldUntilRestorationReturnsEvenWhenTheCachedCredentialIsValid() = runTest(dispatcher) {
        val repository = OptimisticRestoreAuthRepository(
            outcome = AuthSession.Authenticated(AuthUser("uid-cached", "a@b.com")),
        )
        val readyBeforeRestoreReturned = mutableListOf<SessionGateState>()
        val vm = SessionGateViewModel(repository)
        val job = launch {
            vm.gate.collect { state ->
                if (!repository.restoreSessionReturned) readyBeforeRestoreReturned += state
            }
        }

        dispatcher.scheduler.advanceTimeBy(OptimisticRestoreAuthRepository.RESTORE_MILLIS / 2)
        dispatcher.scheduler.runCurrent()
        assertEquals(SessionGateState.Resolving, vm.gate.value)

        dispatcher.scheduler.advanceUntilIdle()
        job.cancel()

        assertIs<SessionGateState.Ready>(vm.gate.value)
        assertTrue(
            readyBeforeRestoreReturned.none { it.allowsUserScopedWork },
            "no state observed before restoreSession() returned may allow user-scoped " +
                "work, saw $readyBeforeRestoreReturned",
        )
    }

    @Test
    fun signingOutFromTheAuthenticatedAreaClosesTheGate() = runTest(dispatcher) {
        val repository = FakeAuthRepository(
            initialAccounts = mapOf("a@b.com" to "secret1"),
            persistedAccountEmail = "a@b.com",
        )
        val vm = SessionGateViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()
        assertIs<SessionGateState.Ready>(vm.gate.value)

        vm.signOut()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(SessionGateState.SignedOut, vm.gate.value)
        assertEquals(1, repository.signOutCount)
    }
}

/** Pure mapping tests: cheap, and they pin the distinction the gate depends on. */
class SessionGateStateTest {

    @Test
    fun unresolvedMapsToResolvingAndIsNotSignedOut() {
        val mapped = AuthSession.Unresolved.toGateState()
        assertEquals(SessionGateState.Resolving, mapped)
        assertFalse(mapped == SessionGateState.SignedOut)
        assertFalse(mapped.allowsUserScopedWork)
    }

    @Test
    fun onlyReadyAllowsUserScopedWork() {
        assertFalse(AuthSession.Unresolved.toGateState().allowsUserScopedWork)
        assertFalse(AuthSession.SignedOut.toGateState().allowsUserScopedWork)
        assertFalse(
            AuthSession.ResolutionFailed(AuthError.SessionExpired).toGateState().allowsUserScopedWork,
        )
        assertTrue(
            AuthSession.Authenticated(AuthUser("uid-1", "a@b.com")).toGateState().allowsUserScopedWork,
        )
    }

    @Test
    fun readyCarriesTheAuthenticatedUserUnchanged() {
        val user = AuthUser("uid-7", "seven@example.com", isEmailVerified = true)
        assertEquals(SessionGateState.Ready(user), AuthSession.Authenticated(user).toGateState())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeAuthRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeAuthRepository(initialAccounts = mapOf("known@example.com" to "secret1"))
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AuthViewModel(repository)

    @Test
    fun signingInWithCorrectCredentialsAuthenticatesAndDropsThePassword() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEmailChange("known@example.com")
        vm.onPasswordChange("secret1")

        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()

        assertIs<AuthSession.Authenticated>(repository.currentSession)
        assertEquals("", vm.uiState.value.password)
        assertNull(vm.uiState.value.authError)
        assertFalse(vm.uiState.value.isSubmitting)
    }

    @Test
    fun aWrongPasswordIsReportedAsAnAuthErrorAndLeavesTheSessionAlone() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEmailChange("known@example.com")
        vm.onPasswordChange("wrong-one")

        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AuthError.InvalidCredentials, vm.uiState.value.authError)
        assertEquals(AuthSession.Unresolved, repository.currentSession)
    }

    @Test
    fun signingUpCreatesTheAccountAndAuthenticatesIt() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onModeChange(AuthMode.SignUp)
        vm.onEmailChange("fresh@example.com")
        vm.onPasswordChange("secret1")
        vm.onConfirmPasswordChange("secret1")

        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()

        val session = assertIs<AuthSession.Authenticated>(repository.currentSession)
        assertEquals("fresh@example.com", session.user.email)
    }

    @Test
    fun mismatchedConfirmationIsALocalFormErrorAndNeverReachesTheRepository() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onModeChange(AuthMode.SignUp)
        vm.onEmailChange("fresh@example.com")
        vm.onPasswordChange("secret1")
        vm.onConfirmPasswordChange("secret2")

        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AuthFormError.PasswordsDoNotMatch, vm.uiState.value.formError)
        assertNull(vm.uiState.value.authError)
        assertEquals(AuthSession.Unresolved, repository.currentSession)
    }

    @Test
    fun anEmptyEmailIsRejectedLocally() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onPasswordChange("secret1")

        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AuthFormError.EmailRequired, vm.uiState.value.formError)
        assertEquals(AuthSession.Unresolved, repository.currentSession)
    }

    @Test
    fun passwordRecoveryReportsSuccessWithoutChangingTheSession() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onModeChange(AuthMode.Recover)
        vm.onEmailChange("known@example.com")

        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("known@example.com", vm.uiState.value.recoveryEmailSentTo)
        assertEquals(listOf("known@example.com"), repository.passwordResetsSent)
        assertEquals(AuthSession.Unresolved, repository.currentSession)
    }

    @Test
    fun passwordRecoveryForAnUnknownAccountReportsUserNotFound() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onModeChange(AuthMode.Recover)
        vm.onEmailChange("nobody@example.com")

        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AuthError.UserNotFound, vm.uiState.value.authError)
        assertNull(vm.uiState.value.recoveryEmailSentTo)
    }

    @Test
    fun switchingModeClearsSecretsAndStaleFeedback() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEmailChange("known@example.com")
        vm.onPasswordChange("wrong-one")
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(AuthError.InvalidCredentials, vm.uiState.value.authError)

        vm.onModeChange(AuthMode.SignUp)

        val state = vm.uiState.value
        assertEquals(AuthMode.SignUp, state.mode)
        assertEquals("", state.password)
        assertEquals("", state.confirmPassword)
        assertNull(state.authError)
        assertNull(state.formError)
        // The email survives, because retyping it is pure friction.
        assertEquals("known@example.com", state.email)
    }

    @Test
    fun recoveryModeNeedsNoPasswordToSubmit() {
        val vm = viewModel()
        vm.onModeChange(AuthMode.Recover)
        vm.onEmailChange("known@example.com")

        val state = vm.uiState.value
        assertFalse(state.requiresPassword)
        assertFalse(state.requiresConfirmation)
        assertTrue(state.canSubmit)
    }
}

/**
 * A test double that reproduces the failure mode faithfully.
 *
 * Deliberately separate from [FakeAuthRepository], which consumes its injected failure
 * after one call and therefore cannot express "resolution keeps failing until the
 * credential is cleared" - the behaviour both real adapters actually have, because
 * neither `restoreSession()` signs out on a hard failure.
 */
private class StuckSessionAuthRepository : AuthRepository {

    private var hasPersistedCredential = true
    var signOutCount: Int = 0
        private set

    private val _session = MutableStateFlow<AuthSession>(AuthSession.Unresolved)
    override val session: Flow<AuthSession> = _session.asStateFlow()

    override suspend fun restoreSession() {
        _session.value = if (hasPersistedCredential) {
            // Note: no sign-out here, exactly like the real adapters.
            AuthSession.ResolutionFailed(AuthError.SessionExpired)
        } else {
            AuthSession.SignedOut
        }
    }

    override suspend fun signUp(email: String, password: String): AuthResult = AuthResult.Success

    override suspend fun signIn(email: String, password: String): AuthResult = AuthResult.Success

    override suspend fun sendPasswordResetEmail(email: String): AuthResult = AuthResult.Success

    override suspend fun signOut(): AuthResult {
        signOutCount++
        hasPersistedCredential = false
        _session.value = AuthSession.SignedOut
        return AuthResult.Success
    }
}

/**
 * Reproduces the cold-start race between the SDK auth-state listener and
 * server-side session validation.
 *
 * Collecting [session] immediately reports a locally cached credential, exactly as both
 * real adapters do when they register their SDK listener. [restoreSession] then takes
 * [RESTORE_MILLIS] to reach the server and publishes [outcome], which may confirm or
 * reject that cached credential.
 */
private class OptimisticRestoreAuthRepository(
    private val outcome: AuthSession,
) : AuthRepository {

    private val cachedUser = AuthUser("uid-cached", "a@b.com")
    private val _session = MutableStateFlow<AuthSession>(AuthSession.Unresolved)

    /** Flipped once the simulated server round trip has finished. */
    var restoreSessionReturned: Boolean = false
        private set

    override val session: Flow<AuthSession> = flow {
        emit(AuthSession.Unresolved)
        // Registering the SDK listener reports the locally cached credential straight
        // away, before restoreSession() has had any chance to validate it.
        _session.value = AuthSession.Authenticated(cachedUser)
        emitAll(_session)
    }

    override suspend fun restoreSession() {
        delay(RESTORE_MILLIS)
        _session.value = outcome
        restoreSessionReturned = true
    }

    override suspend fun signUp(email: String, password: String): AuthResult = AuthResult.Success

    override suspend fun signIn(email: String, password: String): AuthResult = AuthResult.Success

    override suspend fun sendPasswordResetEmail(email: String): AuthResult = AuthResult.Success

    override suspend fun signOut(): AuthResult {
        _session.value = AuthSession.SignedOut
        return AuthResult.Success
    }

    companion object {
        const val RESTORE_MILLIS = 200L
    }
}
