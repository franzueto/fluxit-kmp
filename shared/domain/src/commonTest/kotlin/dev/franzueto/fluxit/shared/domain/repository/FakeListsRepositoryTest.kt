package dev.franzueto.fluxit.shared.domain.repository

import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ListDraft
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
import kotlin.test.assertTrue

class FakeListsRepositoryTest {
    private fun seqIds(): IdGenerator {
        var n = 0
        return IdGenerator {
            n++
            "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
        }
    }

    private fun newRepo(): FakeListsRepository =
        FakeListsRepository(
            ids = seqIds(),
            clock = FakeClock(Instant.fromEpochSeconds(1_700_000_000)),
        )

    private fun draft(name: String = "Groceries") =
        ListDraft(
            name = name,
            icon = FluxItIconRef.CART,
            color = ColorToken.PRIMARY_BLUE,
        )

    @Test
    fun observe_all_starts_empty_and_emits_new_summary_after_create() =
        runTest {
            val repo = newRepo()
            assertEquals(emptyList(), repo.observeAll().first())
            val id = (repo.create(draft()) as Outcome.Ok).value
            val list = repo.observeAll().first()
            assertEquals(1, list.size)
            assertEquals(id, list.single().id)
            assertEquals("Groceries", list.single().name)
        }

    @Test
    fun observe_emits_detail_then_null_after_delete() =
        runTest {
            val repo = newRepo()
            val id = (repo.create(draft()) as Outcome.Ok).value
            assertEquals(id, repo.observe(id).first()?.id)
            assertEquals(Outcome.Ok(Unit), repo.delete(id))
            assertNull(repo.observe(id).first())
        }

    @Test
    fun search_filters_by_lowercase_substring() =
        runTest {
            val repo = newRepo()
            repo.create(draft("Groceries"))
            repo.create(draft("Hardware Store"))
            repo.create(draft("Wishlist"))
            val results = repo.search("STORE").first()
            assertEquals(1, results.size)
            assertEquals("Hardware Store", results.single().name)
        }

    @Test
    fun rename_set_starred_update_appearance_reflected_in_observers() =
        runTest {
            val repo = newRepo()
            val id = (repo.create(draft()) as Outcome.Ok).value
            repo.rename(id, "Pantry")
            repo.setStarred(id, true)
            repo.updateAppearance(id, icon = FluxItIconRef.HOME, color = ColorToken.ACCENT_EMERALD)
            val detail = repo.observe(id).first()!!
            assertEquals("Pantry", detail.name)
            assertTrue(detail.isStarred)
            assertEquals(FluxItIconRef.HOME, detail.icon)
            assertEquals(ColorToken.ACCENT_EMERALD, detail.color)
        }

    @Test
    fun writes_on_missing_or_tombstoned_id_return_not_found() =
        runTest {
            val repo = newRepo()
            val bogus = ListId("00000000-0000-4000-8000-bbbbbbbbbbbb")
            val rename = repo.rename(bogus, "X")
            val notFound = assertIs<Outcome.Err<DataError>>(rename)
            assertEquals(DataError.NotFound(bogus.value), notFound.error)

            // After deletion, the same id is tombstoned — writes also fail.
            val id = (repo.create(draft()) as Outcome.Ok).value
            repo.delete(id)
            val postDelete = repo.rename(id, "Pantry")
            assertIs<Outcome.Err<DataError>>(postDelete)
        }

    @Test
    fun reorder_places_list_between_brackets() =
        runTest {
            val repo = newRepo()
            // Newest-at-top: each create lands above the previous one, so the
            // visible order is [c, b, a]. We then move `c` between `b` and `a`
            // to land in the middle.
            val a = (repo.create(draft("A")) as Outcome.Ok).value
            val b = (repo.create(draft("B")) as Outcome.Ok).value
            val c = (repo.create(draft("C")) as Outcome.Ok).value
            assertEquals(listOf(c, b, a), repo.observeAll().first().map { it.id })
            repo.reorder(c, previous = b, next = a)
            assertEquals(listOf(b, c, a), repo.observeAll().first().map { it.id })
        }

    @Test
    fun newest_at_top_sort_order_after_two_creates() =
        runTest {
            val repo = newRepo()
            val a = (repo.create(draft("A")) as Outcome.Ok).value
            val b = (repo.create(draft("B")) as Outcome.Ok).value
            // Newest first: B above A.
            assertEquals(listOf(b, a), repo.observeAll().first().map { it.id })
        }
}
