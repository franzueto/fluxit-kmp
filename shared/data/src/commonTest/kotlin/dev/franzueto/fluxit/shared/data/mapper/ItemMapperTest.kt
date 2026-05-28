package dev.franzueto.fluxit.shared.data.mapper

import dev.franzueto.fluxit.shared.domain.model.Item
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import dev.franzueto.fluxit.shared.data.db.Item as ItemRow

class ItemMapperTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    @Test
    fun itemRow_to_domain_round_trips_with_photo_reference() {
        val row =
            ItemRow(
                id = "00000000-0000-4000-8000-000000000010",
                list_id = "00000000-0000-4000-8000-000000000001",
                title = "Milk",
                subtitle = "Whole, 1L",
                description = "Organic",
                is_completed = true,
                is_starred = false,
                photo_id = "00000000-0000-4000-8000-000000000099",
                sort_order = 1.0,
                created_at = now,
                updated_at = now,
                deleted_at = null,
            )

        assertEquals(
            Item(
                id = ItemId("00000000-0000-4000-8000-000000000010"),
                listId = ListId("00000000-0000-4000-8000-000000000001"),
                title = "Milk",
                subtitle = "Whole, 1L",
                description = "Organic",
                isCompleted = true,
                isStarred = false,
                photoId = PhotoId("00000000-0000-4000-8000-000000000099"),
                createdAt = now,
                updatedAt = now,
            ),
            row.toDomain(),
        )
    }

    @Test
    fun itemRow_with_null_photo_id_maps_to_null_photoId() {
        val row =
            ItemRow(
                id = "00000000-0000-4000-8000-000000000011",
                list_id = "00000000-0000-4000-8000-000000000001",
                title = "Bread",
                subtitle = null,
                description = null,
                is_completed = false,
                is_starred = false,
                photo_id = null,
                sort_order = 2.0,
                created_at = now,
                updated_at = now,
                deleted_at = null,
            )

        val item = row.toDomain()
        assertNull(item.photoId)
        assertNull(item.subtitle)
        assertNull(item.description)
    }
}
