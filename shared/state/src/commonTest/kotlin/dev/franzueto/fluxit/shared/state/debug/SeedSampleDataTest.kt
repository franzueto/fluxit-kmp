package dev.franzueto.fluxit.shared.state.debug

import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.port.FakeClock
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import dev.franzueto.fluxit.shared.domain.repository.FakeItemsRepository
import dev.franzueto.fluxit.shared.domain.repository.FakeListsRepository
import dev.franzueto.fluxit.shared.domain.repository.ListsRepository
import dev.franzueto.fluxit.shared.domain.usecase.items.AddItem
import dev.franzueto.fluxit.shared.domain.usecase.items.ToggleItemCompleted
import dev.franzueto.fluxit.shared.domain.usecase.lists.CreateList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun seqIds(): IdGenerator {
    var n = 0
    return IdGenerator { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }
}

/** Delegating [ListsRepository] that fails `create` after [failAfter] successful creates. */
private class FailAfterListsRepository(
    private val delegate: ListsRepository,
    private val failAfter: Int,
) : ListsRepository by delegate {
    private var created = 0

    override suspend fun create(draft: ListDraft): Outcome<ListId, DataError> =
        if (created >= failAfter) {
            Outcome.Err(DataError.Storage(RuntimeException("disk full")))
        } else {
            created++
            delegate.create(draft)
        }
}

class SeedSampleDataTest {
    private fun seed(lists: ListsRepository): Triple<SeedSampleData, ListsRepository, FakeItemsRepository> {
        val clock = FakeClock(Instant.fromEpochSeconds(1_700_000_000))
        val items = FakeItemsRepository(ids = seqIds(), clock = clock)
        val useCase =
            SeedSampleData(
                createList = CreateList(lists),
                addItem = AddItem(items),
                toggleItemCompleted = ToggleItemCompleted(items),
            )
        return Triple(useCase, lists, items)
    }

    @Test
    fun seeds_five_lists_each_with_at_least_three_items() =
        runTest {
            val clock = FakeClock(Instant.fromEpochSeconds(1_700_000_000))
            val (useCase, lists, items) = seed(FakeListsRepository(ids = seqIds(), clock = clock))

            val result = useCase()

            assertEquals(Outcome.Ok(5), result)
            val summaries = (lists as FakeListsRepository).observeAll().first()
            assertEquals(5, summaries.size)
            assertEquals(
                setOf("Supermarket", "Home To-Do", "Trip to Japan", "Gift Ideas", "Work Q4 Goals"),
                summaries.map { it.name }.toSet(),
            )
            // Counts live in the items repo (the fakes don't join); verify the §7
            // "3–10 items each" floor by querying each list's section.
            assertTrue(summaries.all { items.observeByList(it.id).first().total >= 3 })
        }

    @Test
    fun marks_some_items_completed() =
        runTest {
            val clock = FakeClock(Instant.fromEpochSeconds(1_700_000_000))
            val (useCase, lists, items) = seed(FakeListsRepository(ids = seqIds(), clock = clock))

            useCase()

            val summaries = (lists as FakeListsRepository).observeAll().first()
            // Supermarket seeds 3 completed of 7; Gift Ideas seeds 0.
            val supermarket = items.observeByList(summaries.first { it.name == "Supermarket" }.id).first()
            assertEquals(7, supermarket.total)
            assertEquals(3, supermarket.completedCount)
            val gifts = items.observeByList(summaries.first { it.name == "Gift Ideas" }.id).first()
            assertEquals(0, gifts.completedCount)
        }

    @Test
    fun stops_and_returns_error_on_first_create_failure() =
        runTest {
            val clock = FakeClock(Instant.fromEpochSeconds(1_700_000_000))
            val delegate = FakeListsRepository(ids = seqIds(), clock = clock)
            val failing = FailAfterListsRepository(delegate, failAfter = 2)
            val (useCase, _, _) = seed(failing)

            val result = useCase()

            assertIs<Outcome.Err<*>>(result)
            // Two lists were created before the third create failed.
            assertEquals(2, delegate.observeAll().first().size)
        }
}
