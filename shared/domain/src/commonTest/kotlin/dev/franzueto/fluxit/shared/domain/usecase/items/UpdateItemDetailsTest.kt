package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Optional
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.ValidationError
import dev.franzueto.fluxit.shared.domain.error.fold
import dev.franzueto.fluxit.shared.domain.model.ItemDraft
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.domain.repository.FakeItemsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UpdateItemDetailsTest {
    private val listId = ListId("list-00000001")

    private suspend fun seedItem(items: FakeItemsRepository): ItemId {
        val draft =
            ItemDraft(
                title = "Milk",
                subtitle = "2%",
                description = "from the corner store",
                photoId = PhotoId("photo-1"),
            )
        return (items.add(listId, draft) as Outcome.Ok).value
    }

    @Test
    fun unset_fields_leave_current_values_untouched() =
        runTest {
            val items = newItemsRepo()
            val id = seedItem(items)
            // Change only the title; everything else is Unset.
            assertEquals(
                Outcome.Ok(Unit),
                UpdateItemDetails(items)(id, title = Optional.Set("Oat Milk")),
            )
            val updated = items.observe(id).first()!!
            assertEquals("Oat Milk", updated.title)
            assertEquals("2%", updated.subtitle)
            assertEquals("from the corner store", updated.description)
            assertEquals(PhotoId("photo-1"), updated.photoId)
        }

    @Test
    fun set_null_clears_a_nullable_field() =
        runTest {
            val items = newItemsRepo()
            val id = seedItem(items)
            assertEquals(
                Outcome.Ok(Unit),
                UpdateItemDetails(items)(
                    id,
                    subtitle = Optional.Set(null),
                    photoId = Optional.Set(null),
                ),
            )
            val updated = items.observe(id).first()!!
            assertEquals("Milk", updated.title)
            assertEquals(null, updated.subtitle)
            assertEquals("from the corner store", updated.description)
            assertEquals(null, updated.photoId)
        }

    @Test
    fun title_is_trimmed_when_supplied() =
        runTest {
            val items = newItemsRepo()
            val id = seedItem(items)
            assertEquals(
                Outcome.Ok(Unit),
                UpdateItemDetails(items)(id, title = Optional.Set("  Eggs  ")),
            )
            assertEquals("Eggs", items.observe(id).first()!!.title)
        }

    @Test
    fun blank_title_is_rejected_at_the_edge() =
        runTest {
            val items = newItemsRepo()
            val id = seedItem(items)
            val err = assertIs<Outcome.Err<DomainError>>(UpdateItemDetails(items)(id, title = Optional.Set("   ")))
            assertEquals(DomainError.Validation(field = "title", rule = ValidationError.Empty), err.error)
            // The rejected write never reached the repo.
            assertEquals("Milk", items.observe(id).first()!!.title)
        }

    @Test
    fun missing_id_is_domain_not_found() =
        runTest {
            val items = newItemsRepo()
            val bogus = ItemId("item-99999999")
            val err = assertIs<Outcome.Err<DomainError>>(UpdateItemDetails(items)(bogus, title = Optional.Set("X")))
            assertEquals(DomainError.NotFound(entity = "Item", id = bogus.value), err.error)
        }

    @Test
    fun fold_use_site_collapses_both_channels() =
        runTest {
            val items = newItemsRepo()
            val id = seedItem(items)
            val message =
                UpdateItemDetails(items)(id, description = Optional.Set("note"))
                    .fold(onOk = { "saved" }, onErr = { "failed: $it" })
            assertEquals("saved", message)
        }
}
