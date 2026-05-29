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

class ToggleItemCompletedTest {
    private val listId = ListId("list-00000001")

    @Test
    fun toggles_false_to_true_then_back() =
        runTest {
            val items = newItemsRepo()
            val id = (items.add(listId, itemDraft("Milk")) as Outcome.Ok).value
            assertEquals(Outcome.Ok(Unit), ToggleItemCompleted(items)(id))
            assertTrue(items.observe(id).first()!!.isCompleted)
            assertEquals(Outcome.Ok(Unit), ToggleItemCompleted(items)(id))
            assertTrue(!items.observe(id).first()!!.isCompleted)
        }

    @Test
    fun missing_id_yields_domain_not_found() =
        runTest {
            val items = newItemsRepo()
            val bogus = ItemId("item-99999999")
            val err = assertIs<Outcome.Err<DomainError>>(ToggleItemCompleted(items)(bogus))
            assertEquals(DomainError.NotFound(entity = "Item", id = bogus.value), err.error)
        }

    @Test
    fun fold_use_site_collapses_both_channels() =
        runTest {
            val items = newItemsRepo()
            val id = (items.add(listId, itemDraft("Milk")) as Outcome.Ok).value
            val message = ToggleItemCompleted(items)(id).fold(onOk = { "toggled" }, onErr = { "failed: $it" })
            assertEquals("toggled", message)
        }
}
