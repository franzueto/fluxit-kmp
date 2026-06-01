package dev.franzueto.fluxit.shared.state.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.franzueto.fluxit.platform.analytics.analyticsModule
import dev.franzueto.fluxit.platform.config.configModule
import dev.franzueto.fluxit.platform.logging.loggingModule
import dev.franzueto.fluxit.shared.data.db.FluxItDatabase
import dev.franzueto.fluxit.shared.domain.port.FakePhotoCapture
import dev.franzueto.fluxit.shared.domain.port.FakePhotoStorage
import dev.franzueto.fluxit.shared.domain.port.FakeReminderScheduler
import dev.franzueto.fluxit.shared.domain.port.PhotoCapture
import dev.franzueto.fluxit.shared.domain.port.PhotoStorage
import dev.franzueto.fluxit.shared.domain.port.ReminderScheduler
import dev.franzueto.fluxit.shared.state.store.AccountStore
import dev.franzueto.fluxit.shared.state.store.CreateListStore
import dev.franzueto.fluxit.shared.state.store.ItemDetailStore
import dev.franzueto.fluxit.shared.state.store.ListDetailStore
import dev.franzueto.fluxit.shared.state.store.ListsDashboardStore
import dev.franzueto.fluxit.shared.state.store.RootStore
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

/**
 * JVM graph test for the ADR-015 composition root: starts the full FluxIt Koin
 * graph and resolves every store, proving the `get()` slots across the platform /
 * domain / data / state modules line up. This is the §8 confidence check (the iOS
 * runtime smoke is Slice C).
 *
 * Phase 06 Slice 6: the graph now runs over the **real** common platform modules
 * (`loggingModule`/`configModule`/`analyticsModule`) plus an in-memory driver.
 * The two OS-context-bound capability modules (`remindersModule()`/`photoModule()`
 * android actuals need an `androidContext()` + WorkManager) are substituted with
 * the `:shared:domain-testing` fakes — those actuals are compile-verified by their
 * own modules' builds and exercised by on-device QA (Slice 8), not here.
 */
class KoinGraphTest {
    @AfterTest
    fun teardown() {
        stopKoin()
    }

    private fun jvmDriverModule() =
        module {
            single<SqlDriver> {
                JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { FluxItDatabase.Schema.create(it) }
            }
        }

    /** Real common platform modules + fakes for the OS-context-bound capability ports. */
    private fun testPlatformModules() =
        listOf(
            loggingModule,
            configModule,
            analyticsModule,
            module {
                single<ReminderScheduler> { FakeReminderScheduler() }
                single<PhotoStorage> { FakePhotoStorage() }
                single<PhotoCapture> { FakePhotoCapture() }
            },
        )

    private fun startGraph() =
        startKoin {
            modules(appModules(platformModules = testPlatformModules()) + jvmDriverModule())
        }.koin

    @Test
    fun fullGraphResolvesEveryStore() {
        val koin = startGraph()

        assertNotNull(koin.get<RootStore>())
        assertNotNull(koin.get<ListsDashboardStore>())
        assertNotNull(koin.get<ListDetailStore>())
        assertNotNull(koin.get<CreateListStore>())
        assertNotNull(koin.get<ItemDetailStore>())
        assertNotNull(koin.get<AccountStore>())
    }

    @Test
    fun rootStoreIsSingletonAndScreenStoresAreFactories() {
        val koin = startGraph()

        // RootStore is a single — same instance across resolves.
        check(koin.get<RootStore>() === koin.get<RootStore>()) { "RootStore must be a singleton" }

        // Per-screen stores are factories — fresh instance per resolve.
        assertNotSame(koin.get<ListsDashboardStore>(), koin.get<ListsDashboardStore>())
    }
}
