package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ListId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObserveListDetailTest {
    @Test
    fun combines_list_header_and_partitioned_items() =
        runTest {
            val lists = newListsRepo()
            val items = newItemsRepo()
            val listId = (lists.create(listDraft("Groceries")) as Outcome.Ok).value
            val milk = (items.add(listId, itemDraft("Milk")) as Outcome.Ok).value
            items.add(listId, itemDraft("Eggs"))
            items.setCompleted(milk, true)

            val view = ObserveListDetail(lists, items)(listId).first()
            assertEquals("Groceries", view.detail?.name)
            assertEquals(listOf("Eggs"), view.items.active.map { it.title })
            assertEquals(listOf("Milk"), view.items.completed.map { it.title })
            assertEquals(2, view.items.total)
            assertEquals(1, view.items.completedCount)
        }

    @Test
    fun missing_list_emits_null_detail_with_empty_items() =
        runTest {
            val lists = newListsRepo()
            val items = newItemsRepo()
            // Never created: a list id the repos don't know about.
            val view = ObserveListDetail(lists, items)(ListId("list-99999999")).first()
            assertNull(view.detail)
            assertEquals(0, view.items.total)
        }
}
