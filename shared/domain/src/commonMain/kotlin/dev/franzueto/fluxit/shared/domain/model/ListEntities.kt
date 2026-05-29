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
        /**
         * Mint a fresh [ListId] via the injected [IdGenerator]. Use cases
         * call this rather than constructing `ListId(idGen.newId())` directly
         * so the id-minting seam stays in one place (Phase 04 §2 / §5).
         */
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

/**
 * Returned by `DeleteList` (Phase 04 §7) so the state layer can stage an
 * undo snackbar without re-querying the now-tombstoned list. Carries the
 * deleted list's identity + name for the toast copy ("Deleted '$name'") and
 * the ids of the reminders cancelled as part of the delete (so a future
 * `UndoDeleteList` — blocked today on a data-layer restore primitive — could
 * reschedule them). It is **not** a persistence shape; it never round-trips
 * through a repository.
 */
public data class DeletedListSummary(
    val id: ListId,
    val name: String,
    val cancelledReminderIds: List<ReminderId>,
)
