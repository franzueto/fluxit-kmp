package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.fold
import dev.franzueto.fluxit.shared.domain.model.ListId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClearCompletedItemsTest {
    private val listId = ListId("list-00000001")

    @Test
    fun clears_only_completed_and_returns_count() =
        runTest {
            val items = newItemsRepo()
            val milk = (items.add(listId, itemDraft("Milk")) as Outcome.Ok).value
            val eggs = (items.add(listId, itemDraft("Eggs")) as Outcome.Ok).value
            items.add(listId, itemDraft("Bread"))
            items.setCompleted(milk, true)
            items.setCompleted(eggs, true)

            val result = ClearCompletedItems(items)(listId)
            assertEquals(2, assertIs<Outcome.Ok<Int>>(result).value)
            val section = items.observeByList(listId).first()
            assertEquals(listOf("Bread"), section.active.map { it.title })
            assertEquals(0, section.completedCount)
        }

    @Test
    fun nothing_completed_returns_zero() =
        runTest {
            val items = newItemsRepo()
            items.add(listId, itemDraft("Milk"))
            assertEquals(0, assertIs<Outcome.Ok<Int>>(ClearCompletedItems(items)(listId)).value)
        }

    @Test
    fun fold_use_site_collapses_both_channels() =
        runTest {
            val items = newItemsRepo()
            val message = ClearCompletedItems(items)(listId).fold(onOk = { "cleared $it" }, onErr = { "failed: $it" })
            assertEquals("cleared 0", message)
        }
}
