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
import kotlin.test.assertTrue

class SetItemStarredTest {
    private val listId = ListId("list-00000001")

    @Test
    fun happy_path_sets_starred() =
        runTest {
            val items = newItemsRepo()
            val id = (items.add(listId, itemDraft("Milk")) as Outcome.Ok).value
            assertEquals(Outcome.Ok(Unit), SetItemStarred(items)(id, true))
            assertTrue(items.observe(id).first()!!.isStarred)
        }

    @Test
    fun missing_id_lifts_data_not_found_to_domain_not_found() =
        runTest {
            val items = newItemsRepo()
            val bogus = ItemId("item-99999999")
            val err = assertIs<Outcome.Err<DomainError>>(SetItemStarred(items)(bogus, true))
            assertEquals(DomainError.NotFound(entity = "Item", id = bogus.value), err.error)
        }

    @Test
    fun fold_use_site_collapses_both_channels() =
        runTest {
            val items = newItemsRepo()
            val id = (items.add(listId, itemDraft("Milk")) as Outcome.Ok).value
            val message = SetItemStarred(items)(id, true).fold(onOk = { "starred" }, onErr = { "failed: $it" })
            assertEquals("starred", message)
        }
}
