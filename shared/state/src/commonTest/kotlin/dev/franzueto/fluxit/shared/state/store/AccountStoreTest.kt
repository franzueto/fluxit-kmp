package dev.franzueto.fluxit.shared.state.store

import app.cash.turbine.test
import dev.franzueto.fluxit.shared.domain.port.AppLogger
import dev.franzueto.fluxit.shared.state.testing.runStoreTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class AccountStoreTest {
    private fun store(env: dev.franzueto.fluxit.shared.state.testing.StoreTestEnv): AccountStore =
        AccountStore(scope = env.scope, logger = AppLogger.NoOp, version = "1.2.3", flags = mapOf("debug" to true))

    @Test
    fun seeds_version_and_flags() =
        runStoreTest {
            val s = store(this)
            assertEquals("1.2.3", s.state.value.version)
            assertEquals(true, s.state.value.flags["debug"])
        }

    @Test
    fun open_settings_emits_navigate_to_settings() =
        runStoreTest {
            val s = store(this)
            s.effects.test {
                s.dispatch(AccountIntent.OpenSettings)
                assertIs<AccountEffect.NavigateToSettings>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun open_about_emits_navigate_to_about() =
        runStoreTest {
            val s = store(this)
            s.effects.test {
                s.dispatch(AccountIntent.OpenAbout)
                assertIs<AccountEffect.NavigateToAbout>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
}
