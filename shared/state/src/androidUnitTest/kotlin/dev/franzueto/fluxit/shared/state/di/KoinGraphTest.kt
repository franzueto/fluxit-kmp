package dev.franzueto.fluxit.shared.state.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.franzueto.fluxit.shared.data.db.FluxItDatabase
import dev.franzueto.fluxit.shared.state.store.AccountStore
import dev.franzueto.fluxit.shared.state.store.CreateListStore
import dev.franzueto.fluxit.shared.state.store.ItemDetailStore
import dev.franzueto.fluxit.shared.state.store.ListDetailStore
import dev.franzueto.fluxit.shared.state.store.ListsDashboardStore
import dev.franzueto.fluxit.shared.state.store.RootStore
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

/**
 * JVM graph test for the ADR-015 composition root: starts the full FluxIt Koin
 * graph over an in-memory SQLite driver and resolves every store, proving the
 * `get()` slots across [domainModule]/[dataModule]/[platformModule]/[stateModule]
 * line up. This is the §8 confidence check (the iOS runtime smoke is Slice C).
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

    @Test
    fun fullGraphResolvesEveryStore() {
        val koin = initKoin(extra = listOf(jvmDriverModule())).koin

        assertNotNull(koin.get<RootStore>())
        assertNotNull(koin.get<ListsDashboardStore>())
        assertNotNull(koin.get<ListDetailStore>())
        assertNotNull(koin.get<CreateListStore>())
        assertNotNull(koin.get<ItemDetailStore>())
        assertNotNull(koin.get<AccountStore>())
    }

    @Test
    fun rootStoreIsSingletonAndScreenStoresAreFactories() {
        val koin = initKoin(extra = listOf(jvmDriverModule())).koin

        // RootStore is a single — same instance across resolves.
        check(koin.get<RootStore>() === koin.get<RootStore>()) { "RootStore must be a singleton" }

        // Per-screen stores are factories — fresh instance per resolve.
        assertNotSame(koin.get<ListsDashboardStore>(), koin.get<ListsDashboardStore>())
    }
}
