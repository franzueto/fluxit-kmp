package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ItemId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObserveItemTest {
    @Test
    fun emits_the_item_then_null_after_delete() =
        runTest {
            val repo = newItemsRepo()
            val listId = (newListsRepo().create(listDraft()) as Outcome.Ok).value
            val id = (repo.add(listId, itemDraft("Milk")) as Outcome.Ok).value
            val observe = ObserveItem(repo)

            assertEquals("Milk", observe(id).first()?.title)

            repo.delete(id)
            assertNull(observe(id).first())
        }

    @Test
    fun emits_null_for_an_unknown_id() =
        runTest {
            val observe = ObserveItem(newItemsRepo())
            assertNull(observe(ItemId("item-99999999")).first())
        }
}
