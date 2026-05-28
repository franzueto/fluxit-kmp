package dev.franzueto.fluxit.shared.data.mapper

import dev.franzueto.fluxit.shared.data.db.SelectWithCounts
import dev.franzueto.fluxit.shared.domain.model.ListDetail
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.ListSummary
import dev.franzueto.fluxit.shared.data.db.List as ListRow

/**
 * Phase 03 §6 — pure mappers from SQLDelight-generated rows to domain
 * entities. No IO, no Clock. The `ListRow` alias dodges the clash with
 * `kotlin.collections.List` (the generated table class shadows it).
 */
internal fun ListRow.toDetail(): ListDetail =
    ListDetail(
        id = ListId(id),
        name = name,
        icon = icon,
        color = color,
        isStarred = is_starred,
        createdAt = created_at,
        updatedAt = updated_at,
    )

internal fun SelectWithCounts.toSummary(): ListSummary =
    ListSummary(
        id = ListId(id),
        name = name,
        icon = icon,
        color = color,
        isStarred = is_starred,
        totalItems = total_items.toInt(),
        completedItems = completed_items.toInt(),
        lastActivityAt = last_activity_at,
    )
