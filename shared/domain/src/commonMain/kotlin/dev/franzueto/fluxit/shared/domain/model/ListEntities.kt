package dev.franzueto.fluxit.shared.domain.model

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

/**
 * Row-level projection of a list with its derived counters; backs the
 * dashboard. Derived via the `selectWithCounts` SQL join — never combined
 * client-side from two flows (Phase 03 §6 invariant).
 */
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

/**
 * The list itself, without item rollups. Returned by `observe(id)` and used
 * by the list-detail header (Phase 08).
 */
public data class ListDetail(
    val id: ListId,
    val name: String,
    val icon: FluxItIconRef,
    val color: ColorToken,
    val isStarred: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
