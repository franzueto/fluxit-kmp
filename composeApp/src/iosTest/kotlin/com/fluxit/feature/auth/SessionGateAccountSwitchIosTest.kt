package com.fluxit.feature.auth

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.fluxit.FakeAuthRepository
import com.fluxit.FakeItemRepository
import com.fluxit.FakeListRepository
import com.fluxit.FakePhotoPicker
import com.fluxit.FakePhotoStorage
import com.fluxit.data.DebugSeeder
import com.fluxit.data.PhotoPicker
import com.fluxit.data.PhotoStorage
import com.fluxit.di.appModule
import com.fluxit.domain.FluxListSummary
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListRepository
import com.fluxit.domain.RepositorySnapshot
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthResult
import com.fluxit.ui.theme.FluxItTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import org.koin.compose.KoinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The account-switch path in [SessionGate], through a real composition.
 *
 * [SessionGate] gets the store for the new scope inside `remember(...)`, which clears the
 * outgoing user's `ViewModelStore` while the composition is still running. The unit tests
 * for [SessionScopedViewModelStores] prove the store is cleared; this proves the composed
 * gate really hands user B a fresh `DashboardViewModel` and releases user A's listener,
 * rather than serving B the screen state A left behind.
 *
 * Runs the real [appModule] graph with in-memory fakes behind it, so a
 * "fresh ViewModel" is observable as a second `DashboardViewModel` being constructed (it
 * asks the module for its [ListRepository] once, and the seeder is replaced so it does not).
 */
@OptIn(ExperimentalTestApi::class)
class SessionGateAccountSwitchIosTest {

    /** Counts how often the dashboard listener is started, and how many are live now. */
    private class RecordingListRepository(
        private val delegate: FakeListRepository,
    ) : ListRepository by delegate {
        var started = 0
            private set
        var active = 0
            private set

        override fun observeListSummariesSnapshot(): Flow<RepositorySnapshot<List<FluxListSummary>>> =
            delegate.observeListSummariesSnapshot()
                .onStart { started++; active++ }
                .onCompletion { active-- }
    }

    private class RootOwner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    @Test
    fun switchingFromAccountAToAccountBGivesBAFreshDashboardAndReleasesAsListener() = runComposeUiTest {
        val auth = FakeAuthRepository(
            initialAccounts = mapOf(USER_A to PASSWORD, USER_B to PASSWORD),
            persistedAccountEmail = USER_A,
        )
        val lists = RecordingListRepository(FakeListRepository())
        var dashboardsConstructed = 0
        val testModule = module {
            single<AuthRepository> { auth }
            // Resolved once per DashboardViewModel construction, so it counts instances.
            factory<ListRepository> { dashboardsConstructed++; lists }
            single<ItemRepository> { FakeItemRepository() }
            // The real seeder would resolve the counting ListRepository above too.
            single { DebugSeeder(FakeListRepository(), FakeItemRepository()) }
            single<PhotoStorage> { FakePhotoStorage() }
            single<PhotoPicker> { FakePhotoPicker() }
        }
        val rootOwner = RootOwner()

        setContent {
            KoinApplication(application = { modules(appModule, testModule) }) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides rootOwner) {
                    FluxItTheme { SessionGate() }
                }
            }
        }

        waitUntil(timeoutMillis = WAIT_MS) { lists.active == 1 }
        assertEquals(1, dashboardsConstructed, "user A's dashboard is built once")
        assertEquals(1, lists.started)

        // A -> B directly, with no signed-out screen in between.
        val result = runBlocking { auth.signIn(USER_B, PASSWORD) }
        assertIs<AuthResult.Success>(result)

        waitUntil(timeoutMillis = WAIT_MS) { lists.started == 2 }
        waitForIdle()
        assertEquals(2, dashboardsConstructed, "user B must get a new DashboardViewModel, not A's")
        assertEquals(1, lists.active, "user A's listener must be released, leaving only B's")
        assertTrue(lists.started == 2, "exactly one listener per user")
    }

    private companion object {
        const val USER_A = "a@example.com"
        const val USER_B = "b@example.com"
        const val PASSWORD = "password1"
        const val WAIT_MS = 10_000L
    }
}
