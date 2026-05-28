package dev.franzueto.fluxit.shared.data.mapper

import dev.franzueto.fluxit.shared.domain.model.Item
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.data.db.Item as ItemRow

internal fun ItemRow.toDomain(): Item =
    Item(
        id = ItemId(id),
        listId = ListId(list_id),
        title = title,
        subtitle = subtitle,
        description = description,
        isCompleted = is_completed,
        isStarred = is_starred,
        photoId = photo_id?.let(::PhotoId),
        createdAt = created_at,
        updatedAt = updated_at,
    )
