package dev.franzueto.fluxit.shared.domain.repository

import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.Item
import dev.franzueto.fluxit.shared.domain.model.ItemDraft
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ItemPatch
import dev.franzueto.fluxit.shared.domain.model.ItemsSection
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.domain.port.Clock
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import dev.franzueto.fluxit.shared.domain.rule.SortOrderArithmetic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

/**
 * In-memory [ItemsRepository] for §7 use-case tests (Phase 04 §11).
 * Mirrors [FakeListsRepository]'s pattern (MutableStateFlow backing,
 * tombstone filtering, sort-order minting via [SortOrderArithmetic],
 * `NotFound` returns for missing-or-tombstoned ids).
 *
 * `observeByList` partitions live items into active/completed
 * sections — matches the §3 `ItemsSection` shape the list-detail
 * screen renders. New items default to the active section
 * (`isCompleted = false`).
 *
 * Cascade semantics are application-layer (ADR-006b) — the
 * `DeleteList` use case soft-deletes each item explicitly; this
 * fake exposes the necessary `delete(itemId)` primitive but does not
 * cascade on its own.
 */
public class FakeItemsRepository(
    private val ids: IdGenerator,
    private val clock: Clock,
) : ItemsRepository {
    private data class Row(
        val id: ItemId,
        val listId: ListId,
        val title: String,
        val subtitle: String?,
        val description: String?,
        val isCompleted: Boolean,
        val isStarred: Boolean,
        val photoId: PhotoId?,
        val sortOrder: Double,
        val createdAt: Instant,
        val updatedAt: Instant,
        val deletedAt: Instant?,
    )

    private val state = MutableStateFlow<List<Row>>(emptyList())

    /**
     * Controllable failure mode (Phase 04 §11). When non-null, [update]
     * short-circuits with this [DataError] before mutating state — lets
     * use-case tests drive the repository-failure branch of flows that compose
     * `update` (e.g. `DetachPhotoFromItem` → `UpdateItemDetails` → `update`).
     */
    public var failUpdateWith: DataError? = null

    // ── reads ────────────────────────────────────────────────────────────

    override fun observeByList(listId: ListId): Flow<ItemsSection> =
        state.map { rows ->
            val live =
                rows
                    .filter { it.listId == listId && it.deletedAt == null }
                    .sortedBy { it.sortOrder }
            val active = live.filter { !it.isCompleted }.map { it.toItem() }
            val completed = live.filter { it.isCompleted }.map { it.toItem() }
            ItemsSection(
                active = active,
                completed = completed,
                total = active.size + completed.size,
                completedCount = completed.size,
            )
        }

    override fun observe(itemId: ItemId): Flow<Item?> =
        state.map { rows ->
            rows.firstOrNull { it.id == itemId && it.deletedAt == null }?.toItem()
        }

    // ── writes ───────────────────────────────────────────────────────────

    override suspend fun add(
        listId: ListId,
        draft: ItemDraft,
    ): Outcome<ItemId, DataError> {
        val now = clock.now()
        val id = ItemId(ids.newId())
        // Active section is sorted ascending by sort_order; new items
        // append to the tail of active so they show at the bottom of
        // the TO BUY section. Matches SqlItemsRepository's append-tail
        // behavior for the inline composer.
        val activeMax =
            state.value
                .filter { it.listId == listId && it.deletedAt == null && !it.isCompleted }
                .maxOfOrNull { it.sortOrder }
        val sortOrder =
            if (activeMax != null) activeMax + 1.0 else SortOrderArithmetic.SEED_SORT_ORDER
        val row =
            Row(
                id = id,
                listId = listId,
                title = draft.title,
                subtitle = draft.subtitle,
                description = draft.description,
                isCompleted = false,
                isStarred = draft.isStarred,
                photoId = draft.photoId,
                sortOrder = sortOrder,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        state.value = state.value + row
        return Outcome.Ok(id)
    }

    override suspend fun update(
        itemId: ItemId,
        patch: ItemPatch,
    ): Outcome<Unit, DataError> {
        failUpdateWith?.let { err -> return Outcome.Err(err) }
        return mutate(itemId) {
            it.copy(
                title = patch.title,
                subtitle = patch.subtitle,
                description = patch.description,
                photoId = patch.photoId,
                updatedAt = clock.now(),
            )
        }
    }

    override suspend fun setCompleted(
        itemId: ItemId,
        completed: Boolean,
    ): Outcome<Unit, DataError> = mutate(itemId) { it.copy(isCompleted = completed, updatedAt = clock.now()) }

    override suspend fun setStarred(
        itemId: ItemId,
        starred: Boolean,
    ): Outcome<Unit, DataError> = mutate(itemId) { it.copy(isStarred = starred, updatedAt = clock.now()) }

    override suspend fun reorder(
        itemId: ItemId,
        previous: ItemId?,
        next: ItemId?,
    ): Outcome<Unit, DataError> {
        val rows = state.value
        val owner =
            rows.firstOrNull { it.id == itemId && it.deletedAt == null }
                ?: return Outcome.Err(DataError.NotFound(itemId.value))
        val prevSort = previous?.let { rows.activeSortOrderInList(owner.listId, it) }
        val nextSort = next?.let { rows.activeSortOrderInList(owner.listId, it) }
        val target = SortOrderArithmetic.between(prevSort, nextSort)
        val moved = mutate(itemId) { it.copy(sortOrder = target, updatedAt = clock.now()) }
        if (moved is Outcome.Ok) {
            val live =
                state.value
                    .filter { it.listId == owner.listId && it.deletedAt == null }
                    .map { it.sortOrder }
                    .sorted()
            if (SortOrderArithmetic.needsCompaction(live)) compact(owner.listId)
        }
        return moved
    }

    override suspend fun delete(itemId: ItemId): Outcome<Unit, DataError> = mutate(itemId) { it.copy(deletedAt = clock.now()) }

    override suspend fun clearCompleted(listId: ListId): Outcome<Int, DataError> {
        val now = clock.now()
        val targets =
            state.value.filter {
                it.listId == listId && it.deletedAt == null && it.isCompleted
            }
        if (targets.isEmpty()) return Outcome.Ok(0)
        val targetIds = targets.mapTo(mutableSetOf()) { it.id }
        state.value =
            state.value.map { r ->
                if (r.id in targetIds) r.copy(deletedAt = now, updatedAt = now) else r
            }
        return Outcome.Ok(targets.size)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun List<Row>.activeSortOrderInList(
        listId: ListId,
        itemId: ItemId,
    ): Double? =
        firstOrNull {
            it.id == itemId && it.listId == listId && it.deletedAt == null
        }?.sortOrder

    private fun mutate(
        itemId: ItemId,
        transform: (Row) -> Row,
    ): Outcome<Unit, DataError> {
        val current = state.value
        if (current.none { it.id == itemId && it.deletedAt == null }) {
            return Outcome.Err(DataError.NotFound(itemId.value))
        }
        state.value =
            current.map { r ->
                if (r.id == itemId && r.deletedAt == null) transform(r) else r
            }
        return Outcome.Ok(Unit)
    }

    private fun compact(listId: ListId) {
        val all = state.value
        val activeSorted =
            all.filter { it.listId == listId && it.deletedAt == null }.sortedBy { it.sortOrder }
        val rest = all.filter { it.listId != listId || it.deletedAt != null }
        val compacted =
            activeSorted.mapIndexed { i, r -> r.copy(sortOrder = (i + 1).toDouble()) }
        state.value = rest + compacted
    }

    private fun Row.toItem(): Item =
        Item(
            id = id,
            listId = listId,
            title = title,
            subtitle = subtitle,
            description = description,
            isCompleted = isCompleted,
            isStarred = isStarred,
            photoId = photoId,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}
