package dev.franzueto.fluxit.shared.state.store

import app.cash.turbine.test
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.port.AppLogger
import dev.franzueto.fluxit.shared.domain.port.SchedulerError
import dev.franzueto.fluxit.shared.domain.usecase.app.InitializeApp
import dev.franzueto.fluxit.shared.domain.usecase.reminders.RehydrateReminders
import dev.franzueto.fluxit.shared.state.navigation.Tab
import dev.franzueto.fluxit.shared.state.testing.StoreTestEnv
import dev.franzueto.fluxit.shared.state.testing.StubReminderScheduler
import dev.franzueto.fluxit.shared.state.testing.StubRemindersRepository
import dev.franzueto.fluxit.shared.state.testing.runStoreTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun StoreTestEnv.rootStore(scheduler: StubReminderScheduler = StubReminderScheduler()): RootStore =
    RootStore(
        scope = scope,
        logger = AppLogger.NoOp,
        initializeApp =
            InitializeApp(
                rehydrateReminders =
                    RehydrateReminders(
                        reminders = StubRemindersRepository(),
                        scheduler = scheduler,
                    ),
            ),
    )

class RootStoreTest {
    @Test
    fun initial_state_is_initializing_on_the_lists_tab() =
        runStoreTest {
            val store = rootStore()
            assertEquals(RootState(init = InitState.Initializing, currentTab = Tab.Lists), store.state.value)
        }

    @Test
    fun app_started_reaches_ready_when_startup_succeeds() =
        runStoreTest {
            val store = rootStore()
            store.state.test {
                assertTrue(awaitItem().init is InitState.Initializing)
                store.dispatch(RootIntent.AppStarted)
                // InitializeApp completes (reminders rehydrated, no orphan sweep) → Ready.
                var init = awaitItem().init
                while (init !is InitState.Ready) {
                    init = awaitItem().init
                }
                assertEquals(InitState.Ready, init)
            }
        }

    @Test
    fun app_started_surfaces_failure_in_state_and_as_a_fatal_effect() =
        runStoreTest {
            val store = rootStore(scheduler = StubReminderScheduler(Outcome.Err(SchedulerError.SystemBusy)))
            store.effects.test {
                store.dispatch(RootIntent.AppStarted)
                val effect = awaitItem()
                assertTrue(effect is RootEffect.ShowFatalError)
                val init = store.state.value.init
                assertTrue(init is InitState.Failed)
                // The message is the §9 SchedulerFailure(SystemBusy) mapping, not empty.
                assertTrue(init.message.isNotBlank())
                assertEquals(init.message, effect.message)
            }
        }

    @Test
    fun tab_selected_updates_the_current_tab() =
        runStoreTest {
            val store = rootStore()
            store.state.test {
                assertEquals(Tab.Lists, awaitItem().currentTab)
                store.dispatch(RootIntent.TabSelected(Tab.Account))
                var tab = awaitItem().currentTab
                while (tab != Tab.Account) {
                    tab = awaitItem().currentTab
                }
                assertEquals(Tab.Account, tab)
            }
        }

    @Test
    fun open_deep_link_to_a_list_emits_navigate_to_list() =
        runStoreTest {
            val store = rootStore()
            store.effects.test {
                store.dispatch(RootIntent.OpenDeepLink("fluxit://list/abc"))
                assertEquals(RootEffect.NavigateToList(ListId("abc")), awaitItem())
            }
        }

    @Test
    fun open_deep_link_to_an_item_emits_navigate_to_item() =
        runStoreTest {
            val store = rootStore()
            store.effects.test {
                store.dispatch(RootIntent.OpenDeepLink("fluxit://item/xyz"))
                assertEquals(RootEffect.NavigateToItem(ItemId("xyz")), awaitItem())
            }
        }

    @Test
    fun open_deep_link_that_is_unparseable_emits_no_effect() =
        runStoreTest {
            val store = rootStore()
            store.effects.test {
                store.dispatch(RootIntent.OpenDeepLink("https://example.com/list/abc"))
                // Drop it: dispatch a known-good link afterwards and assert that
                // it's the only effect we see (the bad one produced nothing).
                store.dispatch(RootIntent.OpenDeepLink("fluxit://list/next"))
                assertEquals(RootEffect.NavigateToList(ListId("next")), awaitItem())
            }
        }
}
