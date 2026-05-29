package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.fold
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ListId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReorderItemTest {
    private val listId = ListId("list-00000001")

    @Test
    fun moves_item_between_brackets() =
        runTest {
            val items = newItemsRepo()
            val a = (items.add(listId, itemDraft("A")) as Outcome.Ok).value
            val b = (items.add(listId, itemDraft("B")) as Outcome.Ok).value
            val c = (items.add(listId, itemDraft("C")) as Outcome.Ok).value
            // Append order → [A, B, C]; move C between A and B.
            assertEquals(
                listOf(a, b, c),
                items
                    .observeByList(listId)
                    .first()
                    .active
                    .map { it.id },
            )
            assertEquals(Outcome.Ok(Unit), ReorderItem(items)(c, previous = a, next = b))
            assertEquals(
                listOf(a, c, b),
                items
                    .observeByList(listId)
                    .first()
                    .active
                    .map { it.id },
            )
        }

    @Test
    fun missing_id_lifts_data_not_found_to_domain_not_found() =
        runTest {
            val items = newItemsRepo()
            val bogus = ItemId("item-99999999")
            val err = assertIs<Outcome.Err<DomainError>>(ReorderItem(items)(bogus, null, null))
            assertEquals(DomainError.NotFound(entity = "Item", id = bogus.value), err.error)
        }

    @Test
    fun fold_use_site_collapses_both_channels() =
        runTest {
            val items = newItemsRepo()
            val a = (items.add(listId, itemDraft("A")) as Outcome.Ok).value
            val message = ReorderItem(items)(a, null, null).fold(onOk = { "moved" }, onErr = { "failed: $it" })
            assertEquals("moved", message)
        }
}
