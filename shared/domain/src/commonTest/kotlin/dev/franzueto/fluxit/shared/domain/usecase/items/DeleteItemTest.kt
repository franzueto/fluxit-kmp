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
import kotlin.test.assertNull

class DeleteItemTest {
    private val listId = ListId("list-00000001")

    @Test
    fun soft_deletes_item_so_observers_drop_it() =
        runTest {
            val items = newItemsRepo()
            val id = (items.add(listId, itemDraft("Milk")) as Outcome.Ok).value
            assertEquals(Outcome.Ok(Unit), DeleteItem(items)(id))
            assertNull(items.observe(id).first())
            assertEquals(0, items.observeByList(listId).first().total)
        }

    @Test
    fun missing_id_lifts_data_not_found_to_domain_not_found() =
        runTest {
            val items = newItemsRepo()
            val bogus = ItemId("item-99999999")
            val err = assertIs<Outcome.Err<DomainError>>(DeleteItem(items)(bogus))
            assertEquals(DomainError.NotFound(entity = "Item", id = bogus.value), err.error)
        }

    @Test
    fun fold_use_site_collapses_both_channels() =
        runTest {
            val items = newItemsRepo()
            val id = (items.add(listId, itemDraft("Milk")) as Outcome.Ok).value
            val message = DeleteItem(items)(id).fold(onOk = { "deleted" }, onErr = { "failed: $it" })
            assertEquals("deleted", message)
        }
}
