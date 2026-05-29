package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.ValidationError
import dev.franzueto.fluxit.shared.domain.error.fold
import dev.franzueto.fluxit.shared.domain.model.ListId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AddItemTest {
    private val listId = ListId("list-00000001")

    @Test
    fun happy_path_appends_trimmed_item_to_active_section() =
        runTest {
            val items = newItemsRepo()
            val result = AddItem(items)(listId, itemDraft("  Milk  "))
            assertIs<Outcome.Ok<*>>(result)
            val section = items.observeByList(listId).first()
            assertEquals(listOf("Milk"), section.active.map { it.title })
        }

    @Test
    fun blank_title_yields_domain_validation_empty() =
        runTest {
            val items = newItemsRepo()
            val result = AddItem(items)(listId, itemDraft("   "))
            val err = assertIs<Outcome.Err<DomainError>>(result)
            assertEquals(DomainError.Validation(field = "title", rule = ValidationError.Empty), err.error)
            assertTrue(
                items
                    .observeByList(listId)
                    .first()
                    .active
                    .isEmpty(),
            )
        }

    @Test
    fun fold_use_site_collapses_both_channels() =
        runTest {
            val items = newItemsRepo()
            val message =
                AddItem(items)(listId, itemDraft("Milk")).fold(
                    onOk = { "added ${it.value}" },
                    onErr = { "failed: $it" },
                )
            assertTrue(message.startsWith("added"))
        }
}
