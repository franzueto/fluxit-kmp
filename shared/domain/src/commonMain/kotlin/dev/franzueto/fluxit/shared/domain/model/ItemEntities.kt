package dev.franzueto.fluxit.shared.domain.model

import kotlinx.datetime.Instant
import kotlin.jvm.JvmInline

@JvmInline
public value class ItemId(
    public val value: String,
) {
    init {
        require(value.isNotEmpty()) { "ItemId must not be empty" }
    }
}

/**
 * Photo id lives alongside item entities because Items reference Photos
 * via FK. The Photos repository slice (Phase 03 §5 (4/4)) will reuse this
 * type — declaring it here keeps Items independent of the Photos slice's
 * landing order.
 */
@JvmInline
public value class PhotoId(
    public val value: String,
) {
    init {
        require(value.isNotEmpty()) { "PhotoId must not be empty" }
    }
}

/** Single item; backs the list-detail row and the edit-item screen. */
public data class Item(
    val id: ItemId,
    val listId: ListId,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val isCompleted: Boolean,
    val isStarred: Boolean,
    val photoId: PhotoId?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Caller-supplied fields for adding a new item. The repository mints the
 * id, sort_order, created_at, and updated_at; is_completed defaults to
 * false (new items start in the TO BUY section).
 */
public data class ItemDraft(
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val photoId: PhotoId? = null,
    val isStarred: Boolean = false,
)

/**
 * Full content replacement for [Item]. The single backing SQL UPDATE writes
 * all four columns atomically — the patch carries every mutable content
 * field rather than offering per-field optionality. Callers that want
 * "leave subtitle alone" read the current item first and re-emit the value.
 */
public data class ItemPatch(
    val title: String,
    val subtitle: String?,
    val description: String?,
    val photoId: PhotoId?,
)

/**
 * Partitioned snapshot of a list's items — backs the list-detail screen
 * (TO BUY + COMPLETED sections + the `13/20` progress counter). Built from
 * the single `selectByListGroupedByStatus` query so the two sections and
 * the rollups share one consistent view of the table.
 */
public data class ItemsSection(
    val active: List<Item>,
    val completed: List<Item>,
    val total: Int,
    val completedCount: Int,
)
