package com.fluxit

import com.fluxit.config.AppFeatures
import com.fluxit.data.DebugSeeder
import com.fluxit.di.appModule
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListRepository
import com.fluxit.domain.auth.AuthError
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthSession
import com.fluxit.feature.auth.AuthMode
import com.fluxit.feature.auth.AuthViewModel
import com.fluxit.feature.dashboard.DashboardViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** Sign-up and sample-data switches (web hides both). */
@OptIn(ExperimentalCoroutinesApi::class)
class AppFeaturesTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var auth: FakeAuthRepository
    private lateinit var lists: FakeListRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        auth = FakeAuthRepository()
        lists = FakeListRepository()
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun mobileAuthKeepsTheSignUpFlow() {
        val vm = AuthViewModel(auth, AppFeatures.Mobile)

        vm.onModeChange(AuthMode.SignUp)

        assertTrue(vm.uiState.value.canSignUp)
        assertEquals(AuthMode.SignUp, vm.uiState.value.mode)
    }

    @Test
    fun webAuthRefusesToSwitchIntoSignUp() {
        val vm = AuthViewModel(auth, AppFeatures.Web)

        vm.onModeChange(AuthMode.SignUp)

        assertFalse(vm.uiState.value.canSignUp)
        assertEquals(AuthMode.SignIn, vm.uiState.value.mode)
    }

    @Test
    fun webAuthStillAllowsPasswordRecovery() {
        val vm = AuthViewModel(auth, AppFeatures.Web)

        vm.onModeChange(AuthMode.Recover)

        assertEquals(AuthMode.Recover, vm.uiState.value.mode)
    }

    @Test
    fun webSubmitForAnUnknownEmailSignsInInsteadOfCreatingAnAccount() = runTest(dispatcher) {
        val vm = AuthViewModel(auth, AppFeatures.Web)
        vm.onModeChange(AuthMode.SignUp)
        vm.onEmailChange("new@example.com")
        vm.onPasswordChange("password123")

        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AuthError.UserNotFound, vm.uiState.value.authError)
        assertIs<AuthSession.Unresolved>(auth.currentSession)
    }

    @Test
    fun dashboardWithoutASeederNeverSeeds() = runTest(dispatcher) {
        val vm = DashboardViewModel(lists, seeder = null, auth)

        vm.seedSampleData()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.canSeedSampleData)
        assertEquals(0, lists.createListCallCount)
    }

    @Test
    fun dashboardWithASeederOffersSeeding() {
        val vm = DashboardViewModel(lists, DebugSeeder(lists, FakeItemRepository()), auth)

        assertTrue(vm.canSeedSampleData)
    }

    @Test
    fun webGraphHidesSignUpAndNeverResolvesTheSeeder() {
        val koin = graph(AppFeatures.Web)

        assertFalse(koin.get<AuthViewModel>().uiState.value.canSignUp)
        assertFalse(koin.get<DashboardViewModel>().canSeedSampleData)
    }

    @Test
    fun mobileGraphKeepsSignUpAndSeeding() {
        val koin = graph(AppFeatures.Mobile)

        assertTrue(koin.get<AuthViewModel>().uiState.value.canSignUp)
        assertTrue(koin.get<DashboardViewModel>().canSeedSampleData)
    }

    /** [appModule] over fakes, with only [features] varying. */
    private fun graph(features: AppFeatures): Koin = koinApplication {
        modules(
            module {
                single { features }
                single<AuthRepository> { auth }
                single<ListRepository> { lists }
                single<ItemRepository> { FakeItemRepository() }
            },
            appModule,
        )
    }.koin
}
