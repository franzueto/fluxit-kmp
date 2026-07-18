package dev.franzueto.fluxit.shared.domain.model

import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import kotlinx.datetime.Instant
import kotlin.jvm.JvmInline

/**
 * Typed wrapper around the underlying UUID-v4 string. Stops `ListId` and
 * `ItemId` from being silently interchangeable at call sites.
 */
@JvmInline
public value class ListId(
    public val value: String,
) {
    init {
        require(value.isNotEmpty()) { "ListId must not be empty" }
    }

    public companion object {
        public fun new(idGen: IdGenerator): ListId = ListId(idGen.newId())
    }
}

/**
 * Caller-supplied fields for creating a new list. The repository mints the
 * id, sort_order, created_at, and updated_at.
 */
public data class ListDraft(
    val name: String,
    val icon: FluxItIconRef,
    val color: ColorToken,
    val isStarred: Boolean = false,
)

public data class ListSummary(
    val id: ListId,
    val name: String,
    val icon: FluxItIconRef,
    val color: ColorToken,
    val isStarred: Boolean,
    val totalItems: Int,
    val completedItems: Int,
    val lastActivityAt: Instant,
)

public data class ListDetail(
    val id: ListId,
    val name: String,
    val icon: FluxItIconRef,
    val color: ColorToken,
    val isStarred: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

public data class DeletedListSummary(
    val id: ListId,
    val name: String,
    val cancelledReminderIds: List<ReminderId>,
)
