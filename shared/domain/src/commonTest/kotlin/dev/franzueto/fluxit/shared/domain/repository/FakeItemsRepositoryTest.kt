package dev.franzueto.fluxit.shared.domain.repository

import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ItemDraft
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ItemPatch
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.port.FakeClock
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FakeItemsRepositoryTest {
    private val listId = ListId("00000000-0000-4000-8000-aaaaaaaaaaaa")
    private val otherListId = ListId("00000000-0000-4000-8000-bbbbbbbbbbbb")

    private fun seqIds(): IdGenerator {
        var n = 0
        return IdGenerator {
            n++
            "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
        }
    }

    private fun newRepo(): FakeItemsRepository =
        FakeItemsRepository(
            ids = seqIds(),
            clock = FakeClock(Instant.fromEpochSeconds(1_700_000_000)),
        )

    private fun draft(title: String = "Milk") = ItemDraft(title = title)

    @Test
    fun add_then_observe_by_list_puts_item_in_active_section() =
        runTest {
            val repo = newRepo()
            val id = (repo.add(listId, draft("Milk")) as Outcome.Ok).value
            val section = repo.observeByList(listId).first()
            assertEquals(1, section.active.size)
            assertEquals(id, section.active.single().id)
            assertEquals(0, section.completed.size)
            assertEquals(1, section.total)
            assertEquals(0, section.completedCount)
        }

    @Test
    fun set_completed_moves_item_between_sections() =
        runTest {
            val repo = newRepo()
            val id = (repo.add(listId, draft()) as Outcome.Ok).value
            repo.setCompleted(id, true)
            val afterComplete = repo.observeByList(listId).first()
            assertEquals(0, afterComplete.active.size)
            assertEquals(1, afterComplete.completed.size)
            assertEquals(1, afterComplete.completedCount)
            assertEquals(1, afterComplete.total)

            repo.setCompleted(id, false)
            val afterUncomplete = repo.observeByList(listId).first()
            assertEquals(1, afterUncomplete.active.size)
            assertEquals(0, afterUncomplete.completed.size)
        }

    @Test
    fun update_applies_patch_fields_atomically() =
        runTest {
            val repo = newRepo()
            val id = (repo.add(listId, draft("Milk")) as Outcome.Ok).value
            repo.update(
                id,
                ItemPatch(title = "Whole Milk", subtitle = "1L", description = "Organic", photoId = null),
            )
            val item = repo.observe(id).first()!!
            assertEquals("Whole Milk", item.title)
            assertEquals("1L", item.subtitle)
            assertEquals("Organic", item.description)
            assertNull(item.photoId)
        }

    @Test
    fun clear_completed_soft_deletes_completed_items_and_returns_count() =
        runTest {
            val repo = newRepo()
            val a = (repo.add(listId, draft("A")) as Outcome.Ok).value
            val b = (repo.add(listId, draft("B")) as Outcome.Ok).value
            val c = (repo.add(listId, draft("C")) as Outcome.Ok).value
            repo.setCompleted(a, true)
            repo.setCompleted(c, true)

            val cleared = repo.clearCompleted(listId)
            assertEquals(Outcome.Ok(2), cleared)

            val section = repo.observeByList(listId).first()
            assertEquals(1, section.active.size)
            assertEquals(b, section.active.single().id)
            assertEquals(0, section.completed.size)
        }

    @Test
    fun clear_completed_on_list_with_no_completed_items_returns_zero() =
        runTest {
            val repo = newRepo()
            repo.add(listId, draft())
            assertEquals(Outcome.Ok(0), repo.clearCompleted(listId))
        }

    @Test
    fun observe_by_list_filters_to_owning_list_only() =
        runTest {
            val repo = newRepo()
            val mine = (repo.add(listId, draft("Mine")) as Outcome.Ok).value
            repo.add(otherListId, draft("Theirs"))
            val mySection = repo.observeByList(listId).first()
            assertEquals(1, mySection.total)
            assertEquals(mine, mySection.active.single().id)
        }

    @Test
    fun writes_on_missing_id_return_not_found() =
        runTest {
            val repo = newRepo()
            val bogus = ItemId("00000000-0000-4000-8000-cccccccccccc")
            val result = repo.setCompleted(bogus, true)
            val err = assertIs<Outcome.Err<DataError>>(result)
            assertEquals(DataError.NotFound(bogus.value), err.error)
        }

    @Test
    fun observe_individual_item_emits_null_after_delete() =
        runTest {
            val repo = newRepo()
            val id = (repo.add(listId, draft()) as Outcome.Ok).value
            assertEquals(id, repo.observe(id).first()?.id)
            repo.delete(id)
            assertNull(repo.observe(id).first())
        }
}
