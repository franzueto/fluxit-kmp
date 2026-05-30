package dev.franzueto.fluxit.shared.state.store

import app.cash.turbine.test
import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.ListSummary
import dev.franzueto.fluxit.shared.domain.port.AppLogger
import dev.franzueto.fluxit.shared.domain.port.FakeClock
import dev.franzueto.fluxit.shared.domain.port.FakeReminderScheduler
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import dev.franzueto.fluxit.shared.domain.repository.FakeListsRepository
import dev.franzueto.fluxit.shared.domain.repository.FakeRemindersRepository
import dev.franzueto.fluxit.shared.domain.repository.ListsRepository
import dev.franzueto.fluxit.shared.domain.usecase.lists.DeleteList
import dev.franzueto.fluxit.shared.domain.usecase.lists.ObserveLists
import dev.franzueto.fluxit.shared.domain.usecase.lists.SearchLists
import dev.franzueto.fluxit.shared.domain.usecase.reminders.CancelReminder
import dev.franzueto.fluxit.shared.state.navigation.Tab
import dev.franzueto.fluxit.shared.state.testing.StoreTestEnv
import dev.franzueto.fluxit.shared.state.testing.runStoreTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/** Sequential, deterministic id generator so minted list ids are predictable. */
private fun seqIds(): IdGenerator {
    var n = 0
    return IdGenerator { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }
}

private fun draft(name: String): ListDraft = ListDraft(name = name, icon = FluxItIconRef.CART, color = ColorToken.PRIMARY_BLUE)

/**
 * Delegating [ListsRepository] that counts `search` subscriptions (to prove the
 * 200ms debounce coalesces a burst into one call) and can inject a `delete`
 * failure (to exercise the optimistic revert path).
 */
private class RecordingListsRepository(
    private val delegate: ListsRepository,
    var failDeleteWith: DataError? = null,
) : ListsRepository by delegate {
    var searchCount = 0

    override fun search(query: String): Flow<List<ListSummary>> {
        searchCount++
        return delegate.search(query)
    }

    override suspend fun delete(id: ListId): Outcome<Unit, DataError> = failDeleteWith?.let { Outcome.Err(it) } ?: delegate.delete(id)
}

private class Fixture(
    val lists: RecordingListsRepository,
    val store: ListsDashboardStore,
) {
    suspend fun seed(vararg names: String): List<ListId> = names.map { (lists.create(draft(it)) as Outcome.Ok).value }
}

private fun StoreTestEnv.fixture(): Fixture {
    val domainClock = FakeClock(Instant.fromEpochSeconds(1_700_000_000))
    val backing = FakeListsRepository(ids = seqIds(), clock = domainClock)
    val lists = RecordingListsRepository(backing)
    val reminders = FakeRemindersRepository(ids = seqIds(), clock = domainClock)
    val scheduler = FakeReminderScheduler()
    val deleteList = DeleteList(lists, reminders, CancelReminder(reminders, scheduler))
    val store =
        ListsDashboardStore(
            scope = scope,
            logger = AppLogger.NoOp,
            observeLists = ObserveLists(lists),
            searchLists = SearchLists(lists),
            deleteList = deleteList,
            clock = clock,
        )
    return Fixture(lists, store)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ListsDashboardStoreTest {
    @Test
    fun initial_state_is_loading_then_loads_the_feed() =
        runStoreTest {
            val f = fixture()
            f.seed("Groceries", "Work")
            f.store.state.test {
                assertEquals(LoadState.Loading, awaitItem().lists)
                val loaded = assertIs<LoadState.Loaded<List<ListSummary>>>(awaitItem().lists)
                assertEquals(setOf("Groceries", "Work"), loaded.value.map { it.name }.toSet())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun empty_feed_resolves_to_empty_not_loading() =
        runStoreTest {
            val f = fixture()
            f.store.state.test {
                assertEquals(LoadState.Loading, awaitItem().lists)
                assertEquals(LoadState.Empty, awaitItem().lists)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun search_query_change_updates_query_synchronously_and_filters() =
        runStoreTest {
            val f = fixture()
            f.seed("Groceries", "Workout", "Garden")
            f.store.state.test {
                // Drain the initial blank-query feed.
                awaitItem() // Loading
                awaitItem() // Loaded(all 3)
                f.store.dispatch(ListsIntent.SearchQueryChanged("gar"))
                // searchQuery reflects the keystroke immediately (synchronous).
                assertEquals("gar", awaitItem().searchQuery)
                val filtered = assertIs<LoadState.Loaded<List<ListSummary>>>(awaitItem().lists)
                assertEquals(listOf("Garden"), filtered.value.map { it.name })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun a_burst_of_keystrokes_debounces_to_a_single_search() =
        runStoreTest {
            val f = fixture()
            f.seed("Apple", "Banana")
            // Let the blank feed settle first (ObserveLists, not search).
            testScope.advanceTimeBy(1)
            testScope.runCurrent()
            assertEquals(0, f.lists.searchCount)

            listOf("a", "ap", "app", "appl", "apple").forEach { f.store.dispatch(ListsIntent.SearchQueryChanged(it)) }
            testScope.runCurrent() // run the 5 reduces (queryFlow updates)
            testScope.advanceTimeBy(199.milliseconds)
            assertEquals(0, f.lists.searchCount) // still within the debounce window
            testScope.advanceTimeBy(2.milliseconds)
            testScope.runCurrent()
            assertEquals(1, f.lists.searchCount)
        }

    @Test
    fun open_list_and_create_emit_navigation_effects() =
        runStoreTest {
            val f = fixture()
            f.store.effects.test {
                f.store.dispatch(ListsIntent.OpenList(ListId("abc")))
                assertEquals(ListsEffect.NavigateToListDetail(ListId("abc")), awaitItem())
                f.store.dispatch(ListsIntent.CreateListClicked)
                assertEquals(ListsEffect.NavigateToCreateList, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun tab_selected_emits_navigate_to_tab() =
        runStoreTest {
            val f = fixture()
            f.store.effects.test {
                f.store.dispatch(ListsIntent.TabSelected(Tab.Calendar))
                assertEquals(ListsEffect.NavigateToTab(Tab.Calendar), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun delete_optimistically_removes_row_opens_undo_window_and_emits_snackbar() =
        runStoreTest {
            val f = fixture()
            val (groceries) = f.seed("Groceries")
            f.store.effects.test {
                f.store.state.test {
                    awaitItem() // Loading
                    assertIs<LoadState.Loaded<List<ListSummary>>>(awaitItem().lists)
                    f.store.dispatch(ListsIntent.DeleteListClicked(groceries))
                    // Optimistic removal flips to Empty first (pendingDelete still
                    // null)…
                    val optimistic = awaitItem()
                    assertEquals(LoadState.Empty, optimistic.lists)
                    assertNull(optimistic.pendingDelete)
                    // …then, on use-case success, the 5s undo window opens.
                    assertEquals(groceries, awaitItem().pendingDelete?.id)
                    cancelAndIgnoreRemainingEvents()
                }
                assertEquals(ListsEffect.ShowUndoSnackbar("Groceries", 5), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun delete_failure_reverts_the_row_and_emits_show_error() =
        runStoreTest {
            val f = fixture()
            val (groceries) = f.seed("Groceries")
            f.lists.failDeleteWith = DataError.Storage(RuntimeException("disk full"))
            f.store.effects.test {
                f.store.state.test {
                    awaitItem() // Loading
                    assertIs<LoadState.Loaded<List<ListSummary>>>(awaitItem().lists)
                    f.store.dispatch(ListsIntent.DeleteListClicked(groceries))
                    // Optimistic removal flips to Empty, then reverts back to Loaded.
                    assertEquals(LoadState.Empty, awaitItem().lists)
                    val reverted = assertIs<LoadState.Loaded<List<ListSummary>>>(awaitItem().lists)
                    assertEquals(listOf("Groceries"), reverted.value.map { it.name })
                    assertNull(f.store.state.value.pendingDelete)
                    cancelAndIgnoreRemainingEvents()
                }
                assertIs<ListsEffect.ShowError>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun undo_window_expires_after_5s_and_clears_pending_delete() =
        runStoreTest {
            val f = fixture()
            val (groceries) = f.seed("Groceries")
            testScope.advanceTimeBy(1)
            testScope.runCurrent()
            f.store.dispatch(ListsIntent.DeleteListClicked(groceries))
            testScope.runCurrent()
            assertEquals(
                groceries,
                f.store.state.value.pendingDelete
                    ?.id,
            )
            testScope.advanceTimeBy(5_000.milliseconds)
            testScope.runCurrent()
            assertNull(f.store.state.value.pendingDelete)
        }

    @Test
    fun explicit_undo_cancels_the_timer_and_dismisses_the_snackbar() =
        runStoreTest {
            val f = fixture()
            val (groceries) = f.seed("Groceries")
            testScope.advanceTimeBy(1)
            testScope.runCurrent()
            f.store.dispatch(ListsIntent.DeleteListClicked(groceries))
            testScope.runCurrent()
            assertEquals(
                groceries,
                f.store.state.value.pendingDelete
                    ?.id,
            )
            f.store.dispatch(ListsIntent.UndoDeleteClicked)
            testScope.runCurrent()
            assertNull(f.store.state.value.pendingDelete)
            // Timer was cancelled: advancing past the window must not throw / re-trigger.
            testScope.advanceTimeBy(5_000.milliseconds)
            testScope.runCurrent()
            assertNull(f.store.state.value.pendingDelete)
        }

    @Test
    fun rapid_second_delete_finalizes_the_first_window_and_opens_a_new_one() =
        runStoreTest {
            val f = fixture()
            val (groceries, work) = f.seed("Groceries", "Work")
            testScope.advanceTimeBy(1)
            testScope.runCurrent()
            f.store.dispatch(ListsIntent.DeleteListClicked(groceries))
            testScope.runCurrent()
            assertEquals(
                groceries,
                f.store.state.value.pendingDelete
                    ?.id,
            )
            // Second delete within the window: the pending window now tracks the
            // second list, and the first timer must not later clear it.
            f.store.dispatch(ListsIntent.DeleteListClicked(work))
            testScope.runCurrent()
            assertEquals(
                work,
                f.store.state.value.pendingDelete
                    ?.id,
            )
            // Advance past the FIRST delete's original deadline — the stale timer
            // targets `groceries`, so the `work` window survives.
            testScope.advanceTimeBy(4_999.milliseconds)
            testScope.runCurrent()
            assertEquals(
                work,
                f.store.state.value.pendingDelete
                    ?.id,
            )
            // Now let the second window expire.
            testScope.advanceTimeBy(5_000.milliseconds)
            testScope.runCurrent()
            assertNull(f.store.state.value.pendingDelete)
        }

    @Test
    fun refresh_reloads_the_feed() =
        runStoreTest {
            val f = fixture()
            f.seed("Groceries")
            f.store.state.test {
                awaitItem() // Loading
                assertIs<LoadState.Loaded<List<ListSummary>>>(awaitItem().lists)
                f.store.dispatch(ListsIntent.Refresh)
                // Refresh flips back to Loading, then re-resolves the feed.
                assertEquals(LoadState.Loading, awaitItem().lists)
                assertIs<LoadState.Loaded<List<ListSummary>>>(awaitItem().lists)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun undo_with_no_pending_delete_is_a_noop() =
        runStoreTest {
            val f = fixture()
            f.store.dispatch(ListsIntent.UndoDeleteClicked)
            testScope.runCurrent()
            assertNull(f.store.state.value.pendingDelete)
        }

    @Test
    fun expire_with_no_pending_delete_is_a_noop() =
        runStoreTest {
            val f = fixture()
            f.store.dispatch(ListsIntent.UndoWindowExpired(ListId("never-deleted")))
            testScope.runCurrent()
            assertNull(f.store.state.value.pendingDelete)
        }

    @Test
    fun delete_before_feed_loads_reverts_without_a_snapshot() =
        runStoreTest {
            val f = fixture()
            val (groceries) = f.seed("Groceries")
            f.lists.failDeleteWith = DataError.Storage(RuntimeException("disk full"))
            // Delete is dispatched before the feed's first emission: lists is still
            // Loading, so the optimistic apply/revert run with no Loaded snapshot.
            f.store.effects.test {
                f.store.dispatch(ListsIntent.DeleteListClicked(groceries))
                assertIs<ListsEffect.ShowError>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertNull(f.store.state.value.pendingDelete)
        }
}
