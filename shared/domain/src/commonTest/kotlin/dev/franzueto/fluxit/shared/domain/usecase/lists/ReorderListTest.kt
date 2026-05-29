package dev.franzueto.fluxit.shared.domain.usecase.lists

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.fold
import dev.franzueto.fluxit.shared.domain.model.ListId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReorderListTest {
    @Test
    fun moves_list_between_brackets() =
        runTest {
            val lists = newRepo()
            val a = (lists.create(draft("A")) as Outcome.Ok).value
            val b = (lists.create(draft("B")) as Outcome.Ok).value
            val c = (lists.create(draft("C")) as Outcome.Ok).value
            // New lists land at the top → dashboard order is [C, B, A].
            assertEquals(
                listOf(c, b, a),
                lists
                    .observeAll()
                    .first()
                    .map { it.id },
            )
            // Move A to sit between C and B → [C, A, B].
            assertEquals(Outcome.Ok(Unit), ReorderList(lists)(a, previous = c, next = b))
            assertEquals(
                listOf(c, a, b),
                lists
                    .observeAll()
                    .first()
                    .map { it.id },
            )
        }

    @Test
    fun missing_id_lifts_data_not_found_to_domain_not_found() =
        runTest {
            val lists = newRepo()
            val bogus = ListId("list-99999999")
            val err = assertIs<Outcome.Err<DomainError>>(ReorderList(lists)(bogus, null, null))
            assertEquals(DomainError.NotFound(entity = "List", id = bogus.value), err.error)
        }

    @Test
    fun fold_use_site_collapses_both_channels() =
        runTest {
            val lists = newRepo()
            val a = (lists.create(draft("A")) as Outcome.Ok).value
            val message = ReorderList(lists)(a, null, null).fold(onOk = { "moved" }, onErr = { "failed: $it" })
            assertEquals("moved", message)
        }
}
