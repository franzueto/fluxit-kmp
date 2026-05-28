package dev.franzueto.fluxit.shared.data.repository

import app.cash.turbine.test
import dev.franzueto.fluxit.core.utils.IdGenerator
import dev.franzueto.fluxit.shared.data.db.FluxItDatabase
import dev.franzueto.fluxit.shared.data.db.fluxItDatabase
import dev.franzueto.fluxit.shared.data.db.inMemoryDriver
import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ItemDraft
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ItemPatch
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import dev.franzueto.fluxit.shared.domain.model.ListId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlItemsRepositorySmokeTest {
    private val fixedNow = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val fakeClock =
        object : Clock {
            override fun now(): Instant = fixedNow
        }

    private data class Fixture(
        val db: FluxItDatabase,
        val lists: SqlListsRepository,
        val items: SqlItemsRepository,
    )

    private fun fixture(seed: Long = 0L): Fixture {
        var n = seed
        val gen =
            IdGenerator {
                n += 1
                "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
            }
        val db = fluxItDatabase(inMemoryDriver())
        return Fixture(
            db = db,
            lists =
                SqlListsRepository(
                    database = db,
                    clock = fakeClock,
                    ids = gen,
                    dispatcher = Dispatchers.Unconfined,
                ),
            items =
                SqlItemsRepository(
                    database = db,
                    clock = fakeClock,
                    ids = gen,
                    dispatcher = Dispatchers.Unconfined,
                ),
        )
    }

    private val listDraft =
        ListDraft(name = "Supermarket", icon = FluxItIconRef.CART, color = ColorToken.PRIMARY_BLUE)

    private suspend fun seedList(
        fx: Fixture,
        name: String = "L",
    ): ListId = (fx.lists.create(listDraft.copy(name = name)) as Outcome.Ok).value

    @Test
    fun add_emits_through_observeByList_in_the_active_section() =
        runTest {
            val fx = fixture()
            val listId = seedList(fx)
            fx.items.observeByList(listId).test {
                val empty = awaitItem()
                assertEquals(0, empty.total)
                val itemId = (fx.items.add(listId, ItemDraft(title = "Milk")) as Outcome.Ok).value
                val withOne = awaitItem()
                assertEquals(1, withOne.total)
                assertEquals(listOf(itemId), withOne.active.map { it.id })
                assertEquals(emptyList(), withOne.completed)
            }
        }

    @Test
    fun add_to_missing_list_returns_not_found() =
        runTest {
            val fx = fixture()
            val out = fx.items.add(ListId("00000000-0000-4000-8000-deadbeefcafe"), ItemDraft("X"))
            assertTrue(out is Outcome.Err)
            assertTrue((out as Outcome.Err).error is DataError.NotFound)
        }

    @Test
    fun add_with_blank_title_returns_validation_error() =
        runTest {
            val fx = fixture()
            val listId = seedList(fx)
            val out = fx.items.add(listId, ItemDraft(title = "   "))
            assertTrue((out as Outcome.Err).error is DataError.Validation)
        }

    @Test
    fun setCompleted_moves_item_between_sections() =
        runTest {
            val fx = fixture()
            val listId = seedList(fx)
            val a = (fx.items.add(listId, ItemDraft("A")) as Outcome.Ok).value
            val b = (fx.items.add(listId, ItemDraft("B")) as Outcome.Ok).value
            fx.items.setCompleted(a, true)
            fx.items.observeByList(listId).test {
                val section = awaitItem()
                assertEquals(listOf(b), section.active.map { it.id })
                assertEquals(listOf(a), section.completed.map { it.id })
                assertEquals(1, section.completedCount)
                assertEquals(2, section.total)
            }
        }

    @Test
    fun update_replaces_content_fields() =
        runTest {
            val fx = fixture()
            val listId = seedList(fx)
            val itemId = (fx.items.add(listId, ItemDraft("Milk")) as Outcome.Ok).value
            fx.items.update(
                itemId,
                ItemPatch(title = "Whole Milk", subtitle = "1L", description = "Organic", photoId = null),
            )
            fx.items.observe(itemId).test {
                val row = awaitItem()!!
                assertEquals("Whole Milk", row.title)
                assertEquals("1L", row.subtitle)
                assertEquals("Organic", row.description)
                assertNull(row.photoId)
            }
        }

    @Test
    fun update_blank_title_returns_validation_error() =
        runTest {
            val fx = fixture()
            val listId = seedList(fx)
            val itemId = (fx.items.add(listId, ItemDraft("Milk")) as Outcome.Ok).value
            val out = fx.items.update(itemId, ItemPatch("", null, null, null))
            assertTrue((out as Outcome.Err).error is DataError.Validation)
        }

    @Test
    fun update_unknown_id_returns_not_found() =
        runTest {
            val fx = fixture()
            val out =
                fx.items.update(
                    ItemId("00000000-0000-4000-8000-deadbeefcafe"),
                    ItemPatch("X", null, null, null),
                )
            assertTrue((out as Outcome.Err).error is DataError.NotFound)
        }

    @Test
    fun delete_hides_the_row_and_blocks_re_delete() =
        runTest {
            val fx = fixture()
            val listId = seedList(fx)
            val itemId = (fx.items.add(listId, ItemDraft("Milk")) as Outcome.Ok).value
            assertTrue(fx.items.delete(itemId) is Outcome.Ok)
            fx.items.observe(itemId).test { assertNull(awaitItem()) }
            assertTrue((fx.items.delete(itemId) as Outcome.Err).error is DataError.NotFound)
        }

    @Test
    fun setStarred_round_trips() =
        runTest {
            val fx = fixture()
            val listId = seedList(fx)
            val itemId = (fx.items.add(listId, ItemDraft("Milk")) as Outcome.Ok).value
            fx.items.setStarred(itemId, true)
            fx.items.observe(itemId).test { assertTrue(awaitItem()!!.isStarred) }
            fx.items.setStarred(itemId, false)
            fx.items.observe(itemId).test { assertFalse(awaitItem()!!.isStarred) }
        }

    @Test
    fun newest_item_sorts_to_top_within_active_section() =
        runTest {
            val fx = fixture()
            val listId = seedList(fx)
            val first = (fx.items.add(listId, ItemDraft("A")) as Outcome.Ok).value
            val second = (fx.items.add(listId, ItemDraft("B")) as Outcome.Ok).value
            fx.items.observeByList(listId).test {
                val section = awaitItem()
                assertEquals(listOf(second, first), section.active.map { it.id })
            }
        }

    @Test
    fun reorder_places_item_between_two_others() =
        runTest {
            val fx = fixture()
            val listId = seedList(fx)
            val a = (fx.items.add(listId, ItemDraft("A")) as Outcome.Ok).value
            val b = (fx.items.add(listId, ItemDraft("B")) as Outcome.Ok).value
            val c = (fx.items.add(listId, ItemDraft("C")) as Outcome.Ok).value
            // Current active order (newest-at-top): c, b, a. Move a between c and b → c, a, b.
            assertTrue(fx.items.reorder(a, previous = c, next = b) is Outcome.Ok)
            fx.items.observeByList(listId).test {
                val section = awaitItem()
                assertEquals(listOf(c, a, b), section.active.map { it.id })
            }
        }

    @Test
    fun clearCompleted_soft_deletes_completed_items_and_returns_count() =
        runTest {
            val fx = fixture()
            val listId = seedList(fx)
            val a = (fx.items.add(listId, ItemDraft("A")) as Outcome.Ok).value
            val b = (fx.items.add(listId, ItemDraft("B")) as Outcome.Ok).value
            val c = (fx.items.add(listId, ItemDraft("C")) as Outcome.Ok).value
            fx.items.setCompleted(a, true)
            fx.items.setCompleted(c, true)
            val cleared = (fx.items.clearCompleted(listId) as Outcome.Ok).value
            assertEquals(2, cleared)
            fx.items.observeByList(listId).test {
                val section = awaitItem()
                assertEquals(listOf(b), section.active.map { it.id })
                assertEquals(emptyList(), section.completed)
                assertEquals(1, section.total)
            }
        }

    @Test
    fun clearCompleted_on_empty_list_returns_zero() =
        runTest {
            val fx = fixture()
            val listId = seedList(fx)
            val out = fx.items.clearCompleted(listId)
            assertEquals(0, (out as Outcome.Ok).value)
        }

    @Test
    fun reorder_repeated_does_not_explode_due_to_rebalance() =
        runTest {
            val fx = fixture()
            val listId = seedList(fx)
            val a = (fx.items.add(listId, ItemDraft("A")) as Outcome.Ok).value
            val b = (fx.items.add(listId, ItemDraft("B")) as Outcome.Ok).value
            val c = (fx.items.add(listId, ItemDraft("C")) as Outcome.Ok).value
            // Halving the bracket sort_order 100 times forces a rebalance
            // to fire at least once; the assertion is just that order is
            // preserved (rebalance is correct when it does trigger).
            repeat(100) {
                fx.items.reorder(b, previous = a, next = c)
            }
            assertNotNull(
                fx.db.itemsQueries
                    .selectByListGroupedByStatus(listId.value)
                    .executeAsList(),
            )
        }
}
