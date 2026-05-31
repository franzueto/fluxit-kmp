package dev.franzueto.fluxit.shared.state.store

import app.cash.turbine.test
import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ItemDraft
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ItemsSection
import dev.franzueto.fluxit.shared.domain.model.ListDetail
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.port.AppLogger
import dev.franzueto.fluxit.shared.domain.port.FakeClock
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import dev.franzueto.fluxit.shared.domain.repository.FakeItemsRepository
import dev.franzueto.fluxit.shared.domain.repository.FakeListsRepository
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository
import dev.franzueto.fluxit.shared.domain.usecase.items.AddItem
import dev.franzueto.fluxit.shared.domain.usecase.items.ClearCompletedItems
import dev.franzueto.fluxit.shared.domain.usecase.items.DeleteItem
import dev.franzueto.fluxit.shared.domain.usecase.items.ObserveListDetail
import dev.franzueto.fluxit.shared.domain.usecase.items.ToggleItemCompleted
import dev.franzueto.fluxit.shared.state.testing.StoreTestEnv
import dev.franzueto.fluxit.shared.state.testing.runStoreTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Sequential, deterministic id generator so minted ids are predictable. */
private fun detailSeqIds(): IdGenerator {
    var n = 0
    return IdGenerator { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }
}

private fun detailListDraft(name: String): ListDraft = ListDraft(name = name, icon = FluxItIconRef.CART, color = ColorToken.PRIMARY_BLUE)

/**
 * Delegating [ItemsRepository] that can inject failures for `setCompleted`, `add`,
 * and `delete` to exercise the optimistic-revert / keep-text-on-failure paths.
 */
private class RecordingItemsRepository(
    private val delegate: ItemsRepository,
    var failSetCompletedWith: DataError? = null,
    var failAddWith: DataError? = null,
    var failDeleteWith: DataError? = null,
    var failClearCompletedWith: DataError? = null,
) : ItemsRepository by delegate {
    override suspend fun clearCompleted(listId: ListId): Outcome<Int, DataError> =
        failClearCompletedWith?.let { Outcome.Err(it) } ?: delegate.clearCompleted(listId)

    override suspend fun setCompleted(
        itemId: ItemId,
        completed: Boolean,
    ): Outcome<Unit, DataError> = failSetCompletedWith?.let { Outcome.Err(it) } ?: delegate.setCompleted(itemId, completed)

    override suspend fun add(
        listId: ListId,
        draft: ItemDraft,
    ): Outcome<ItemId, DataError> = failAddWith?.let { Outcome.Err(it) } ?: delegate.add(listId, draft)

    override suspend fun delete(itemId: ItemId): Outcome<Unit, DataError> =
        failDeleteWith?.let { Outcome.Err(it) } ?: delegate.delete(itemId)
}

private class DetailFixture(
    val items: RecordingItemsRepository,
    val store: ListDetailStore,
    val listId: ListId,
) {
    private val raw: ItemsRepository = items

    suspend fun seedItems(vararg titles: String): List<ItemId> = titles.map { (raw.add(listId, ItemDraft(title = it)) as Outcome.Ok).value }
}

private suspend fun StoreTestEnv.detailFixture(seedList: Boolean = true): DetailFixture {
    val domainClock = FakeClock(Instant.fromEpochSeconds(1_700_000_000))
    val lists = FakeListsRepository(ids = detailSeqIds(), clock = domainClock)
    val items = RecordingItemsRepository(FakeItemsRepository(ids = detailSeqIds(), clock = domainClock))
    val listId =
        if (seedList) {
            (lists.create(detailListDraft("Groceries")) as Outcome.Ok).value
        } else {
            ListId("00000000-0000-4000-8000-000000000999")
        }
    val store =
        ListDetailStore(
            scope = scope,
            logger = AppLogger.NoOp,
            observeListDetail = ObserveListDetail(lists, items),
            toggleItemCompleted = ToggleItemCompleted(items),
            addItem = AddItem(items),
            deleteItem = DeleteItem(items),
            clearCompletedItems = ClearCompletedItems(items),
            clock = clock,
        )
    return DetailFixture(items, store, listId)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ListDetailStoreTest {
    @Test
    fun init_loads_header_and_sections() =
        runStoreTest {
            val f = detailFixture()
            f.seedItems("Milk", "Bread")
            f.store.state.test {
                assertEquals(LoadState.Loading, awaitItem().header)
                f.store.dispatch(ListDetailIntent.Init(f.listId))
                // Drain until both header and sections resolve.
                var s = awaitItem()
                while (s.header !is LoadState.Loaded || s.sections !is LoadState.Loaded) s = awaitItem()
                assertEquals("Groceries", assertIs<LoadState.Loaded<ListDetail>>(s.header).value.name)
                val section = assertIs<LoadState.Loaded<ItemsSection>>(s.sections).value
                assertEquals(setOf("Milk", "Bread"), section.active.map { it.title }.toSet())
                assertEquals(2, section.total)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun a_missing_list_resolves_header_to_error() =
        runStoreTest {
            val f = detailFixture(seedList = false)
            f.store.state.test {
                awaitItem() // initial Loading
                f.store.dispatch(ListDetailIntent.Init(f.listId))
                var s = awaitItem()
                while (s.header is LoadState.Loading) s = awaitItem()
                assertIs<LoadState.Error>(s.header)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun empty_list_resolves_sections_to_empty() =
        runStoreTest {
            val f = detailFixture()
            f.store.state.test {
                awaitItem()
                f.store.dispatch(ListDetailIntent.Init(f.listId))
                var s = awaitItem()
                while (s.sections is LoadState.Loading) s = awaitItem()
                assertEquals(LoadState.Empty, s.sections)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun completion_toggle_moves_item_to_completed_optimistically() =
        runStoreTest {
            val f = detailFixture()
            val (milk) = f.seedItems("Milk")
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            f.store.state.test {
                // Settle on the loaded feed.
                var s = awaitItem()
                while (s.sections !is LoadState.Loaded) s = awaitItem()
                f.store.dispatch(ListDetailIntent.ItemCompletionToggled(milk))
                var done = false
                while (!done) {
                    val section = assertIs<LoadState.Loaded<ItemsSection>>(awaitItem().sections).value
                    if (section.completed.any { it.id == milk }) {
                        assertEquals(1, section.completedCount)
                        assertTrue(section.active.none { it.id == milk })
                        done = true
                    }
                }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun completion_toggle_reverts_and_shows_error_on_failure() =
        runStoreTest {
            val f = detailFixture()
            val (milk) = f.seedItems("Milk")
            f.items.failSetCompletedWith = DataError.Storage(RuntimeException("nope"))
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(ListDetailIntent.ItemCompletionToggled(milk))
                assertIs<ListDetailEffect.ShowError>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            // After the revert + reconcile, the item is back in active, not completed.
            val section = assertIs<LoadState.Loaded<ItemsSection>>(f.store.state.value.sections).value
            assertTrue(section.active.any { it.id == milk })
            assertEquals(0, section.completedCount)
        }

    @Test
    fun composer_submit_adds_item_and_clears_text() =
        runStoreTest {
            val f = detailFixture()
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            f.store.dispatch(ListDetailIntent.ComposerTextChanged("Eggs"))
            testScope.runCurrent()
            assertEquals("Eggs", f.store.state.value.composerText)
            f.store.dispatch(ListDetailIntent.ComposerSubmit)
            testScope.runCurrent()
            assertEquals("", f.store.state.value.composerText)
            val section = assertIs<LoadState.Loaded<ItemsSection>>(f.store.state.value.sections).value
            assertTrue(section.active.any { it.title == "Eggs" })
        }

    @Test
    fun blank_composer_submit_is_a_noop() =
        runStoreTest {
            val f = detailFixture()
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            f.store.dispatch(ListDetailIntent.ComposerTextChanged("   "))
            f.store.dispatch(ListDetailIntent.ComposerSubmit)
            testScope.runCurrent()
            // Still blank, and the feed stayed empty (no item added).
            assertEquals("   ", f.store.state.value.composerText)
            assertEquals(LoadState.Empty, f.store.state.value.sections)
        }

    @Test
    fun composer_failure_keeps_text_and_sets_inline_error() =
        runStoreTest {
            val f = detailFixture()
            f.items.failAddWith = DataError.Storage(RuntimeException("disk full"))
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            f.store.dispatch(ListDetailIntent.ComposerTextChanged("Eggs"))
            f.store.dispatch(ListDetailIntent.ComposerSubmit)
            testScope.runCurrent()
            assertEquals("Eggs", f.store.state.value.composerText)
            assertEquals(true, f.store.state.value.composerError != null)
        }

    @Test
    fun item_delete_opens_undo_window_then_expires() =
        runStoreTest {
            val f = detailFixture()
            val (milk) = f.seedItems("Milk")
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(ListDetailIntent.ItemDeleteClicked(milk))
                assertIs<ListDetailEffect.ShowUndoSnackbar>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(
                milk,
                f.store.state.value.pendingDelete
                    ?.id,
            )
            // Timer fires at 5s → UndoWindowExpired clears the window.
            testScope.advanceTimeBy(6.seconds)
            testScope.runCurrent()
            assertNull(f.store.state.value.pendingDelete)
        }

    @Test
    fun rapid_second_delete_finalizes_the_first_window_and_opens_a_new_one() =
        runStoreTest {
            val f = detailFixture()
            val (milk, bread) = f.seedItems("Milk", "Bread")
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            f.store.dispatch(ListDetailIntent.ItemDeleteClicked(milk))
            testScope.runCurrent()
            f.store.dispatch(ListDetailIntent.ItemDeleteClicked(bread))
            testScope.runCurrent()
            // The open window now belongs to the second delete.
            assertEquals(
                bread,
                f.store.state.value.pendingDelete
                    ?.id,
            )
        }

    @Test
    fun clear_completed_removes_completed_items_via_feed() =
        runStoreTest {
            val f = detailFixture()
            val (milk, bread) = f.seedItems("Milk", "Bread")
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            // Complete both, then clear.
            f.store.dispatch(ListDetailIntent.ItemCompletionToggled(milk))
            f.store.dispatch(ListDetailIntent.ItemCompletionToggled(bread))
            testScope.runCurrent()
            f.store.dispatch(ListDetailIntent.ClearCompletedClicked)
            testScope.runCurrent()
            val sections = f.store.state.value.sections
            // Everything cleared → Empty.
            assertEquals(LoadState.Empty, sections)
        }

    @Test
    fun navigation_and_toggle_show_completed_intents() =
        runStoreTest {
            val f = detailFixture()
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            val before = f.store.state.value.showCompleted
            f.store.dispatch(ListDetailIntent.ToggleShowCompleted)
            testScope.runCurrent()
            assertEquals(!before, f.store.state.value.showCompleted)
            f.store.effects.test {
                f.store.dispatch(ListDetailIntent.BackClicked)
                assertIs<ListDetailEffect.NavigateBack>(awaitItem())
                f.store.dispatch(ListDetailIntent.MoreClicked)
                assertIs<ListDetailEffect.OpenListMenu>(awaitItem())
                f.store.dispatch(ListDetailIntent.ItemTapped(ItemId("some-item")))
                assertIs<ListDetailEffect.NavigateToEditItem>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun undo_item_delete_dismisses_the_snackbar() =
        runStoreTest {
            val f = detailFixture()
            val (milk) = f.seedItems("Milk")
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            f.store.dispatch(ListDetailIntent.ItemDeleteClicked(milk))
            testScope.runCurrent()
            assertEquals(
                milk,
                f.store.state.value.pendingDelete
                    ?.id,
            )
            f.store.dispatch(ListDetailIntent.UndoItemDeleteClicked)
            testScope.runCurrent()
            assertNull(f.store.state.value.pendingDelete)
        }

    @Test
    fun undo_with_no_pending_delete_is_a_noop() =
        runStoreTest {
            val f = detailFixture()
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            f.store.dispatch(ListDetailIntent.UndoItemDeleteClicked)
            testScope.runCurrent()
            assertNull(f.store.state.value.pendingDelete)
        }

    @Test
    fun toggle_before_sections_loaded_is_a_noop() =
        runStoreTest {
            val f = detailFixture()
            // No Init → sections stays Loading, so the toggle early-returns.
            f.store.dispatch(ListDetailIntent.ItemCompletionToggled(ItemId("nope")))
            testScope.runCurrent()
            assertEquals(LoadState.Loading, f.store.state.value.sections)
        }

    @Test
    fun composer_submit_before_init_is_a_noop() =
        runStoreTest {
            val f = detailFixture()
            // listId is unset until Init → submit early-returns even with text.
            f.store.dispatch(ListDetailIntent.ComposerTextChanged("Eggs"))
            f.store.dispatch(ListDetailIntent.ComposerSubmit)
            testScope.runCurrent()
            assertEquals("Eggs", f.store.state.value.composerText)
        }

    @Test
    fun clear_completed_before_init_is_a_noop() =
        runStoreTest {
            val f = detailFixture()
            f.store.dispatch(ListDetailIntent.ClearCompletedClicked)
            testScope.runCurrent()
            // No crash, nothing cleared (listId unset → early return).
            assertEquals(LoadState.Loading, f.store.state.value.sections)
        }

    @Test
    fun clear_completed_failure_emits_show_error() =
        runStoreTest {
            val f = detailFixture()
            f.seedItems("Milk")
            f.items.failClearCompletedWith = DataError.Storage(RuntimeException("nope"))
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(ListDetailIntent.ClearCompletedClicked)
                assertIs<ListDetailEffect.ShowError>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun item_delete_failure_reverts_and_shows_error_without_a_window() =
        runStoreTest {
            val f = detailFixture()
            val (milk) = f.seedItems("Milk")
            f.items.failDeleteWith = DataError.Storage(RuntimeException("disk full"))
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(ListDetailIntent.ItemDeleteClicked(milk))
                assertIs<ListDetailEffect.ShowError>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            // Delete failed → no undo window opens.
            assertNull(f.store.state.value.pendingDelete)
        }

    @Test
    fun toggling_a_completed_item_moves_it_back_to_active() =
        runStoreTest {
            val f = detailFixture()
            val (milk) = f.seedItems("Milk")
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            // Complete it…
            f.store.dispatch(ListDetailIntent.ItemCompletionToggled(milk))
            testScope.runCurrent()
            var section = assertIs<LoadState.Loaded<ItemsSection>>(f.store.state.value.sections).value
            assertTrue(section.completed.any { it.id == milk })
            // …then toggle the completed item back to active.
            f.store.dispatch(ListDetailIntent.ItemCompletionToggled(milk))
            testScope.runCurrent()
            section = assertIs<LoadState.Loaded<ItemsSection>>(f.store.state.value.sections).value
            assertTrue(section.active.any { it.id == milk })
            assertEquals(0, section.completedCount)
        }

    @Test
    fun a_second_init_is_ignored() =
        runStoreTest {
            val f = detailFixture()
            f.seedItems("Milk")
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            // Second Init must not re-subscribe (feedJob guard) — feed stays valid.
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            assertIs<LoadState.Loaded<ItemsSection>>(f.store.state.value.sections)
        }

    @Test
    fun expire_with_unknown_id_leaves_the_window_open() =
        runStoreTest {
            val f = detailFixture()
            val (milk) = f.seedItems("Milk")
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            f.store.dispatch(ListDetailIntent.ItemDeleteClicked(milk))
            testScope.runCurrent()
            // A stale expiry for a different id must not retire the live window.
            f.store.dispatch(ListDetailIntent.UndoWindowExpired(ItemId("00000000-0000-4000-8000-000000009999")))
            testScope.runCurrent()
            assertEquals(
                milk,
                f.store.state.value.pendingDelete
                    ?.id,
            )
        }

    @Test
    fun expire_with_no_pending_delete_is_a_noop() =
        runStoreTest {
            val f = detailFixture()
            f.store.dispatch(ListDetailIntent.Init(f.listId))
            testScope.runCurrent()
            f.store.dispatch(ListDetailIntent.UndoWindowExpired(ItemId("nope")))
            testScope.runCurrent()
            assertNull(f.store.state.value.pendingDelete)
        }

    @Test
    fun item_delete_before_load_reverts_without_a_snapshot() =
        runStoreTest {
            val f = detailFixture()
            val (milk) = f.seedItems("Milk")
            f.items.failDeleteWith = DataError.Storage(RuntimeException("disk full"))
            // Delete dispatched before Init: sections is still Loading, so the
            // optimistic apply/revert run with no Loaded snapshot and a null title.
            f.store.effects.test {
                f.store.dispatch(ListDetailIntent.ItemDeleteClicked(milk))
                assertIs<ListDetailEffect.ShowError>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertNull(f.store.state.value.pendingDelete)
        }
}
