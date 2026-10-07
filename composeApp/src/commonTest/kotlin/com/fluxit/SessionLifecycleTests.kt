package com.fluxit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fluxit.domain.auth.AuthError
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthResult
import com.fluxit.domain.auth.AuthSession
import com.fluxit.domain.auth.AuthUser
import com.fluxit.feature.auth.AuthViewModel
import com.fluxit.feature.auth.SessionGateState
import com.fluxit.feature.auth.SessionGateViewModel
import com.fluxit.feature.auth.SessionScope
import com.fluxit.feature.auth.SessionScopedViewModelStores
import com.fluxit.feature.auth.allowsUserScopedWork
import com.fluxit.feature.auth.sessionScope
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Session lifecycle coverage: listener disposal, user-scoped state teardown, user A -> B
 * isolation, and the 10-second bound on initial restoration.
 *
 * ## What this file proves, and what it does not
 *
 * is the standing warning these tests are written against: asserting "the
 * listener was released" against [FakeAuthRepository] proves nothing, because its
 * `session` is a plain `MutableStateFlow` that is always live regardless of collectors.
 * So the disposal assertions here use [ListenerInstrumentedAuthRepository], whose flow
 * counts its own `onStart`/`onCompletion`, i.e. the exact lifecycle hooks both real
 * adapters hang their `callbackFlow { ... awaitClose { registration.remove() } }` on.
 *
 * That gives a genuine, machine-checked proof that **the gate releases its collection**,
 * and therefore that any `awaitClose`-registered SDK listener would be removed. It is
 * still not a proof that `FirebaseAuth`/`FIRAuth` actually drops its listener - that is
 * SDK behaviour, evidenced by reading `AndroidAuthRepository.session` /
 * `IosAuthRepository.session` and by the manual two-platform matrix, not by this suite.
 *
 * Likewise, nothing here composes anything: this module has no Compose UI test harness.
 * The teardown boundary is therefore tested where it is implemented - in
 * [SessionScopedViewModelStores], a plain class - and its wiring into the composition
 * (`SessionGate` providing `LocalViewModelStoreOwner`) is covered by the manual matrix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionScopedViewModelStoresTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theSameScopeKeepsTheSameStoreAcrossRepeatedRequests() {
        val stores = SessionScopedViewModelStores()

        val first = stores.storeFor(SessionScope.User("uid-a"))
        val second = stores.storeFor(SessionScope.User("uid-a"))

        assertSame(first, second, "an ordinary recomposition must not destroy the store")
        assertEquals(SessionScope.User("uid-a"), stores.activeScope)
    }

    @Test
    fun switchingFromUserAToUserBClearsEveryViewModelUserAOwned() {
        val stores = SessionScopedViewModelStores()
        val probeA = probeIn(stores.storeFor(SessionScope.User("uid-a")))
        assertFalse(probeA.cleared)

        stores.storeFor(SessionScope.User("uid-b"))

        assertTrue(probeA.cleared, "user A's ViewModels must not survive into user B's session")
        assertEquals(SessionScope.User("uid-b"), stores.activeScope)
    }

    @Test
    fun signingOutClearsTheUserScopeAndSigningInClearsTheSignedOutScope() {
        val stores = SessionScopedViewModelStores()

        val userProbe = probeIn(stores.storeFor(SessionScope.User("uid-a")))
        stores.storeFor(SessionScope.SignedOut)
        assertTrue(userProbe.cleared, "sign-out must clear user-scoped state")

        val signedOutProbe = probeIn(stores.storeFor(SessionScope.SignedOut))
        stores.storeFor(SessionScope.User("uid-b"))
        assertTrue(signedOutProbe.cleared, "signing in must clear the signed-out shell's state")
    }

    @Test
    fun clearingTheHolderClearsTheActiveScope() {
        val stores = SessionScopedViewModelStores()
        val probe = probeIn(stores.storeFor(SessionScope.User("uid-a")))

        stores.clearActiveScope()

        assertTrue(probe.cleared)
        assertEquals(null, stores.activeScope)
        // Idempotent: a second clear is a no-op, not a crash.
        stores.clearActiveScope()
    }

    /**
     * The teardown that actually matters for A user-scoped ViewModel's
     * `viewModelScope` is cancelled, so the data listener it was running is released.
     * [DashboardViewModel][com.fluxit.feature.dashboard.DashboardViewModel] holds exactly
     * this shape - a repository flow collected in `viewModelScope`.
     */
    @Test
    fun clearingAUserScopeReleasesTheDataListenersItsViewModelsWereRunning() = runTest(dispatcher) {
        val stores = SessionScopedViewModelStores()
        val source = InstrumentedFlow()
        val probe = ViewModelProvider.create(
            store = stores.storeFor(SessionScope.User("uid-a")),
            factory = viewModelFactory { initializer { ListeningProbeViewModel(source) } },
        )[ListeningProbeViewModel::class]

        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, source.activeCollections, "the probe must be listening to begin with")

        stores.storeFor(SessionScope.SignedOut)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(probe.cleared)
        assertEquals(0, source.activeCollections, "sign-out must release user-scoped listeners")
        assertEquals(1, source.completedCollections)
    }

    @Test
    fun everyNonReadyGateStateMapsToTheSignedOutScope() {
        assertEquals(SessionScope.SignedOut, SessionGateState.Resolving.sessionScope())
        assertEquals(SessionScope.SignedOut, SessionGateState.SignedOut.sessionScope())
        assertEquals(
            SessionScope.SignedOut,
            SessionGateState.ResolutionFailed(AuthError.SessionExpired).sessionScope(),
        )
        assertEquals(
            SessionScope.User("uid-a"),
            SessionGateState.Ready(AuthUser("uid-a", "a@b.com")).sessionScope(),
        )
    }

    private fun probeIn(store: ViewModelStore): ProbeViewModel =
        ViewModelProvider.create(
            store = store,
            factory = viewModelFactory { initializer { ProbeViewModel() } },
        )[ProbeViewModel::class]
}

/** The gate's hold on the repository's auth-state listener. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListenerDisposalTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /**
     * The disposal proof says [FakeAuthRepository] cannot give. Clearing the
     * store that owns the gate cancels its `viewModelScope`, which ends the collection,
     * which is what runs `awaitClose { registration.remove() }` in both real adapters.
     */
    @Test
    fun clearingTheGateViewModelReleasesTheSessionListener() = runTest(dispatcher) {
        val repository = ListenerInstrumentedAuthRepository(persistedUser = AuthUser("uid-a", "a@b.com"))
        val store = ViewModelStore()
        ViewModelProvider.create(
            store = store,
            factory = viewModelFactory { initializer { SessionGateViewModel(repository) } },
        )[SessionGateViewModel::class]
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repository.registrations)
        assertEquals(1, repository.activeListeners)

        store.clear()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, repository.activeListeners, "the gate must release the SDK listener")
        assertEquals(1, repository.disposals)
    }

    /**
     * Stated as an explicit expectation rather than left implicit: the auth-state
     * listener is *not* user-scoped. It is the app's single observer of who is signed in,
     * and it must survive sign-out or the app could never notice the next sign-in. What
     * sign-out disposes is user-scoped state, covered by
     * [SessionScopedViewModelStoresTest].
     */
    @Test
    fun signingOutDoesNotReleaseTheSessionListenerBecauseItIsNotUserScoped() = runTest(dispatcher) {
        val repository = ListenerInstrumentedAuthRepository(persistedUser = AuthUser("uid-a", "a@b.com"))
        val vm = SessionGateViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()
        assertIs<SessionGateState.Ready>(vm.gate.value)

        vm.signOut()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(SessionGateState.SignedOut, vm.gate.value)
        assertEquals(1, repository.activeListeners)
        assertEquals(1, repository.registrations, "sign-out must not re-register the listener either")
    }
}

/** Headline criterion: no A-derived state is visible to B. */
@OptIn(ExperimentalCoroutinesApi::class)
class UserIsolationTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /**
     * The full A -> sign out -> B arc, driven through the same objects the application
     * root uses: the gate decides the scope, the scope owns the ViewModels.
     */
    @Test
    fun signingOutOfAAndIntoBLeavesNoStateFromA() = runTest(dispatcher) {
        val repository = FakeAuthRepository(
            initialAccounts = mapOf("a@x.com" to "secret1", "b@x.com" to "secret2"),
        )
        val stores = SessionScopedViewModelStores()
        val gate = SessionGateViewModel(repository)
        val auth = AuthViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(SessionGateState.SignedOut, gate.gate.value)

        // --- user A signs in -------------------------------------------------------
        auth.onEmailChange("a@x.com")
        auth.onPasswordChange("secret1")
        auth.submit()
        dispatcher.scheduler.advanceUntilIdle()

        val readyA = assertIs<SessionGateState.Ready>(gate.gate.value)
        assertEquals("a@x.com", readyA.user.email)
        val userDataA = probeIn(stores.storeFor(readyA.sessionScope()))
        userDataA.payload = "A's lists"

        // --- user A signs out ------------------------------------------------------
        gate.signOut()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(SessionGateState.SignedOut, gate.gate.value)
        stores.storeFor(gate.gate.value.sessionScope())

        assertTrue(userDataA.cleared, "A's user-scoped ViewModels must be cleared on sign-out")

        // The auth form user B meets is a fresh one: A's email cannot still be sitting
        // in it, because the signed-out scope A left behind was destroyed too.
        val formForB = ViewModelProvider.create(
            store = stores.storeFor(SessionScope.SignedOut),
            factory = viewModelFactory { initializer { AuthViewModel(repository) } },
        )[AuthViewModel::class]
        assertEquals("", formForB.uiState.value.email)
        assertEquals("", formForB.uiState.value.password)

        // --- user B signs in -------------------------------------------------------
        formForB.onEmailChange("b@x.com")
        formForB.onPasswordChange("secret2")
        formForB.submit()
        dispatcher.scheduler.advanceUntilIdle()

        val readyB = assertIs<SessionGateState.Ready>(gate.gate.value)
        assertEquals("b@x.com", readyB.user.email)
        assertTrue(readyA.user.uid != readyB.user.uid, "A and B must not share a uid")

        val userDataB = probeIn(stores.storeFor(readyB.sessionScope()))
        assertNotSame(userDataA, userDataB, "B must not be handed A's ViewModel instance")
        assertEquals(null, userDataB.payload, "B must not see A's data")
        assertEquals(SessionScope.User(readyB.user.uid), stores.activeScope)
    }

    /**
     * The narrower half of the same criterion, isolated so a regression points at the
     * right thing: the auth form itself is state that must not cross a session boundary.
     */
    @Test
    fun aPreviousUsersEmailDoesNotSurviveIntoTheNextSignedOutScope() {
        val repository = FakeAuthRepository(initialAccounts = mapOf("a@x.com" to "secret1"))
        val stores = SessionScopedViewModelStores()

        val formForA = authViewModelIn(stores.storeFor(SessionScope.SignedOut), repository)
        formForA.onEmailChange("a@x.com")
        assertEquals("a@x.com", formForA.uiState.value.email)

        // A signs in, then signs out again.
        stores.storeFor(SessionScope.User("uid-a"))
        val formAfterSignOut = authViewModelIn(stores.storeFor(SessionScope.SignedOut), repository)

        assertNotSame(formForA, formAfterSignOut)
        assertEquals("", formAfterSignOut.uiState.value.email)
    }

    private fun authViewModelIn(store: ViewModelStore, repository: AuthRepository): AuthViewModel =
        ViewModelProvider.create(
            store = store,
            factory = viewModelFactory { initializer { AuthViewModel(repository) } },
        )[AuthViewModel::class]

    private fun probeIn(store: ViewModelStore): ProbeViewModel =
        ViewModelProvider.create(
            store = store,
            factory = viewModelFactory { initializer { ProbeViewModel() } },
        )[ProbeViewModel::class]
}

/** The initial restoration is bounded, and its fallback is signed-out. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRestorationTimeoutTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theTimeoutIsTheTenSecondProductConstantDecidedInDec006() {
        assertEquals(10.seconds, SessionGateViewModel.InitialRestorationTimeout)
    }

    /**
     * The scenario. Everything here runs on virtual time: the suite never
     * actually waits ten seconds.
     */
    @Test
    fun aRestorationThatNeverCompletesFallsBackToSignedOutAfterTheTimeout() = runTest(dispatcher) {
        val repository = HangingRestoreAuthRepository()
        val observed = mutableListOf<SessionGateState>()
        val vm = SessionGateViewModel(repository)
        val job = launch { vm.gate.collect { observed += it } }

        // Right up to, but not including, the deadline: still resolving, still locked.
        dispatcher.scheduler.advanceTimeBy(
            SessionGateViewModel.InitialRestorationTimeout - 1.milliseconds,
        )
        dispatcher.scheduler.runCurrent()
        assertEquals(SessionGateState.Resolving, vm.gate.value)
        assertFalse(vm.restoreTimedOut.value)

        dispatcher.scheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(SessionGateState.SignedOut, vm.gate.value)
        assertTrue(vm.restoreTimedOut.value, "the auth screen needs to know why it is showing")
        assertTrue(
            observed.none { it.allowsUserScopedWork },
            "a timeout must never unlock user-scoped work, saw $observed",
        )
        assertTrue(
            observed.none { it is SessionGateState.ResolutionFailed },
            "the 10-second restoration timeout fallback is signed-out, not an error screen, saw $observed",
        )
    }

    /**
     * Timing out is not permission to trust the cached credential the SDK listener
     * reported while restoration was still hanging.
     */
    @Test
    fun aTimeoutFallsBackToSignedOutEvenWhenACachedCredentialWasAlreadyReported() = runTest(dispatcher) {
        val repository = HangingRestoreAuthRepository(
            cachedSession = AuthSession.Authenticated(AuthUser("uid-cached", "a@b.com")),
        )
        val observed = mutableListOf<SessionGateState>()
        val vm = SessionGateViewModel(repository)
        val job = launch { vm.gate.collect { observed += it } }

        dispatcher.scheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(SessionGateState.SignedOut, vm.gate.value)
        assertTrue(
            observed.none { it.allowsUserScopedWork },
            "an unvalidated cached credential must not become Ready on timeout, saw $observed",
        )
    }

    /** The retry affordance offered after the restoration timeout actually re-runs restoration. */
    @Test
    fun retryingAfterATimeoutReopensTheGateAndCanSucceed() = runTest(dispatcher) {
        val repository = HangingRestoreAuthRepository(
            resolvedUser = AuthUser("uid-a", "a@b.com"),
        )
        val vm = SessionGateViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(SessionGateState.SignedOut, vm.gate.value)
        assertTrue(vm.restoreTimedOut.value)

        vm.retryInitialRestoration()
        dispatcher.scheduler.runCurrent()

        // The gate reopens: back to Resolving, notice withdrawn, fresh budget.
        assertEquals(SessionGateState.Resolving, vm.gate.value)
        assertFalse(vm.restoreTimedOut.value)

        // This attempt is allowed to complete.
        repository.releaseRestore()
        dispatcher.scheduler.advanceUntilIdle()

        val ready = assertIs<SessionGateState.Ready>(vm.gate.value)
        assertEquals("uid-a", ready.user.uid)
        assertFalse(vm.restoreTimedOut.value)
    }

    /**
     * A restoration that returns without ever publishing an outcome
     * used to leave the gate reporting whatever stale value it happened to hold. It now
     * waits for a resolved value, under the same budget.
     */
    @Test
    fun aRestorationThatReturnsWithoutResolvingAnythingIsStillBounded() = runTest(dispatcher) {
        val repository = SilentRestoreAuthRepository()
        val vm = SessionGateViewModel(repository)

        dispatcher.scheduler.advanceTimeBy(
            SessionGateViewModel.InitialRestorationTimeout - 1.milliseconds,
        )
        dispatcher.scheduler.runCurrent()
        assertEquals(SessionGateState.Resolving, vm.gate.value)

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(SessionGateState.SignedOut, vm.gate.value)
        assertTrue(vm.restoreTimedOut.value)
    }

    /** A late outcome from the hung call supersedes the fallback rather than being ignored. */
    @Test
    fun aResolutionArrivingAfterTheTimeoutSupersedesTheFallback() = runTest(dispatcher) {
        val repository = SilentRestoreAuthRepository()
        val vm = SessionGateViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(SessionGateState.SignedOut, vm.gate.value)
        assertTrue(vm.restoreTimedOut.value)

        repository.publish(AuthSession.Authenticated(AuthUser("uid-late", "late@b.com")))
        dispatcher.scheduler.advanceUntilIdle()

        assertIs<SessionGateState.Ready>(vm.gate.value)
        assertFalse(vm.restoreTimedOut.value)
    }
}

// --------------------------------------------------------------------------------------
// Test doubles
// --------------------------------------------------------------------------------------

/** A ViewModel that records its own teardown. */
private open class ProbeViewModel : ViewModel() {
    var payload: String? = null
    var cleared: Boolean = false
        private set

    override fun onCleared() {
        cleared = true
    }
}

/** A ViewModel holding a live listener in its `viewModelScope`, like `DashboardViewModel`. */
private class ListeningProbeViewModel(source: InstrumentedFlow) : ProbeViewModel() {
    init {
        viewModelScope.launch { source.flow.collect { } }
    }
}

/** A flow that counts how many collections are currently active. */
private class InstrumentedFlow {
    var activeCollections: Int = 0
        private set
    var completedCollections: Int = 0
        private set

    private val values = MutableStateFlow(0)

    val flow: Flow<Int> = values.asStateFlow()
        .onStart { activeCollections++ }
        .onCompletion {
            activeCollections--
            completedCollections++
        }
}

/**
 * The repository asks for: one that can actually observe listener lifetime.
 *
 * `session` mirrors both real adapters' shape - a listener registered when collection
 * starts and released when it ends - so `activeListeners` means the same thing here as
 * `registration.remove()` in `awaitClose` means there.
 */
private class ListenerInstrumentedAuthRepository(
    private val persistedUser: AuthUser? = null,
) : AuthRepository {

    var registrations: Int = 0
        private set
    var disposals: Int = 0
        private set
    var activeListeners: Int = 0
        private set

    private val state = MutableStateFlow<AuthSession>(AuthSession.Unresolved)

    override val session: Flow<AuthSession> = state.asStateFlow()
        .onStart {
            registrations++
            activeListeners++
        }
        .onCompletion {
            disposals++
            activeListeners--
        }

    override suspend fun restoreSession() {
        state.value = persistedUser?.let { AuthSession.Authenticated(it) } ?: AuthSession.SignedOut
    }

    override suspend fun signUp(email: String, password: String): AuthResult = AuthResult.Success

    override suspend fun signIn(email: String, password: String): AuthResult = AuthResult.Success

    override suspend fun sendPasswordResetEmail(email: String): AuthResult = AuthResult.Success

    override suspend fun signOut(): AuthResult {
        state.value = AuthSession.SignedOut
        return AuthResult.Success
    }
}

/**
 * `restoreSession` that never returns until released - the hang, made
 * deterministic. [cachedSession] is published first when set, reproducing an SDK listener
 * that reports a locally cached credential the server has not validated.
 */
private class HangingRestoreAuthRepository(
    private val cachedSession: AuthSession? = null,
    private val resolvedUser: AuthUser? = null,
) : AuthRepository {

    private val state = MutableStateFlow<AuthSession>(AuthSession.Unresolved)
    private val gate = CompletableDeferred<Unit>()

    override val session: Flow<AuthSession> = state.asStateFlow()

    /** Lets the *next* `restoreSession()` call complete. */
    fun releaseRestore() {
        gate.complete(Unit)
    }

    override suspend fun restoreSession() {
        cachedSession?.let { state.value = it }
        gate.await()
        state.value = resolvedUser?.let { AuthSession.Authenticated(it) } ?: AuthSession.SignedOut
    }

    override suspend fun signUp(email: String, password: String): AuthResult = AuthResult.Success

    override suspend fun signIn(email: String, password: String): AuthResult = AuthResult.Success

    override suspend fun sendPasswordResetEmail(email: String): AuthResult = AuthResult.Success

    override suspend fun signOut(): AuthResult {
        state.value = AuthSession.SignedOut
        return AuthResult.Success
    }
}

/**
 * `restoreSession()` returns promptly but publishes nothing at all - the "returned
 * without an observable outcome" case that the `Unit` return type permits.
 */
private class SilentRestoreAuthRepository : AuthRepository {

    private val state = MutableStateFlow<AuthSession>(AuthSession.Unresolved)

    override val session: Flow<AuthSession> = state.asStateFlow()

    fun publish(session: AuthSession) {
        state.value = session
    }

    override suspend fun restoreSession() = Unit

    override suspend fun signUp(email: String, password: String): AuthResult = AuthResult.Success

    override suspend fun signIn(email: String, password: String): AuthResult = AuthResult.Success

    override suspend fun sendPasswordResetEmail(email: String): AuthResult = AuthResult.Success

    override suspend fun signOut(): AuthResult {
        state.value = AuthSession.SignedOut
        return AuthResult.Success
    }
}
