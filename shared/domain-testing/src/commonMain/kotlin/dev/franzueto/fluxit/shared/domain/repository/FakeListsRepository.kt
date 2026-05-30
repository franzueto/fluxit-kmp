package dev.franzueto.fluxit.shared.domain.repository

import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ListDetail
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.ListSummary
import dev.franzueto.fluxit.shared.domain.port.Clock
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import dev.franzueto.fluxit.shared.domain.rule.SortOrderArithmetic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

/**
 * In-memory [ListsRepository] for §7 use-case tests (Phase 04 §11).
 * Backed by a `MutableStateFlow<List<Row>>` so observers see live
 * updates. Implements:
 *
 * - Tombstone filtering: reads exclude `deletedAt != null` rows.
 * - Sort-order minting + compaction via [SortOrderArithmetic].
 * - `NotFound` returns on writes to missing-or-tombstoned ids.
 *
 * Counters (`totalItems`, `completedItems`, `lastActivityAt` rollup
 * from items) are NOT computed by this fake — use-case tests combine
 * this fake with [FakeItemsRepository] via `flow.combine` when they
 * need the dashboard projection (matches the Phase 04 §7
 * `ObserveListDetail` shape). Rollups default to zero + the list's
 * own `updatedAt`.
 *
 * Cascade semantics (ADR-006b) are NOT implemented here — per the
 * ADR, application-level cascade across Lists → Items → Reminders is
 * a use-case-layer concern. The `DeleteList` use case (Phase 04 §7)
 * orchestrates by calling each repo's `delete` / `cancel` in turn.
 */
public class FakeListsRepository(
    private val ids: IdGenerator,
    private val clock: Clock,
) : ListsRepository {
    private data class Row(
        val id: ListId,
        val name: String,
        val icon: FluxItIconRef,
        val color: ColorToken,
        val isStarred: Boolean,
        val sortOrder: Double,
        val createdAt: Instant,
        val updatedAt: Instant,
        val deletedAt: Instant?,
    )

    private val state = MutableStateFlow<List<Row>>(emptyList())

    // ── reads ────────────────────────────────────────────────────────────

    override fun observeAll(): Flow<List<ListSummary>> =
        state.map { rows ->
            rows.activeSortedBySortOrder().map { it.toSummary() }
        }

    override fun observe(id: ListId): Flow<ListDetail?> =
        state.map { rows ->
            rows.firstOrNull { it.id == id && it.deletedAt == null }?.toDetail()
        }

    override fun search(query: String): Flow<List<ListSummary>> {
        val q = query.trim().lowercase()
        return state.map { rows ->
            rows
                .activeSortedBySortOrder()
                .filter { q.isEmpty() || it.name.lowercase().contains(q) }
                .map { it.toSummary() }
        }
    }

    // ── writes ───────────────────────────────────────────────────────────

    override suspend fun create(draft: ListDraft): Outcome<ListId, DataError> {
        val now = clock.now()
        val id = ListId(ids.newId())
        // New lists land at the top of the dashboard per the §12 row 5
        // resolution (newest-at-top sort_order; see SqlListsRepository).
        val minSort =
            state.value
                .activeSortedBySortOrder()
                .firstOrNull()
                ?.sortOrder
        val sortOrder =
            if (minSort != null) minSort - 1.0 else SortOrderArithmetic.SEED_SORT_ORDER
        val row =
            Row(
                id = id,
                name = draft.name,
                icon = draft.icon,
                color = draft.color,
                isStarred = draft.isStarred,
                sortOrder = sortOrder,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        state.value = state.value + row
        return Outcome.Ok(id)
    }

    override suspend fun rename(
        id: ListId,
        name: String,
    ): Outcome<Unit, DataError> = mutate(id) { it.copy(name = name, updatedAt = clock.now()) }

    override suspend fun updateAppearance(
        id: ListId,
        icon: FluxItIconRef,
        color: ColorToken,
    ): Outcome<Unit, DataError> = mutate(id) { it.copy(icon = icon, color = color, updatedAt = clock.now()) }

    override suspend fun setStarred(
        id: ListId,
        starred: Boolean,
    ): Outcome<Unit, DataError> = mutate(id) { it.copy(isStarred = starred, updatedAt = clock.now()) }

    override suspend fun reorder(
        id: ListId,
        previous: ListId?,
        next: ListId?,
    ): Outcome<Unit, DataError> {
        val rows = state.value
        val prevSort = previous?.let { rows.activeSortOrderOf(it) }
        val nextSort = next?.let { rows.activeSortOrderOf(it) }
        val target = SortOrderArithmetic.between(prevSort, nextSort)
        val moved = mutate(id) { it.copy(sortOrder = target, updatedAt = clock.now()) }
        if (moved is Outcome.Ok) {
            val active = state.value.activeSortedBySortOrder().map { it.sortOrder }
            if (SortOrderArithmetic.needsCompaction(active)) compact()
        }
        return moved
    }

    override suspend fun delete(id: ListId): Outcome<Unit, DataError> = mutate(id) { it.copy(deletedAt = clock.now()) }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun List<Row>.activeSortedBySortOrder(): List<Row> = filter { it.deletedAt == null }.sortedBy { it.sortOrder }

    private fun List<Row>.activeSortOrderOf(id: ListId): Double? = firstOrNull { it.id == id && it.deletedAt == null }?.sortOrder

    private fun mutate(
        id: ListId,
        transform: (Row) -> Row,
    ): Outcome<Unit, DataError> {
        val current = state.value
        if (current.none { it.id == id && it.deletedAt == null }) {
            return Outcome.Err(DataError.NotFound(id.value))
        }
        state.value =
            current.map { r ->
                if (r.id == id && r.deletedAt == null) transform(r) else r
            }
        return Outcome.Ok(Unit)
    }

    private fun compact() {
        val rows = state.value
        val activeSorted = rows.activeSortedBySortOrder()
        val deleted = rows.filter { it.deletedAt != null }
        state.value =
            activeSorted.mapIndexed { i, r -> r.copy(sortOrder = (i + 1).toDouble()) } + deleted
    }

    private fun Row.toSummary(): ListSummary =
        ListSummary(
            id = id,
            name = name,
            icon = icon,
            color = color,
            isStarred = isStarred,
            totalItems = 0,
            completedItems = 0,
            lastActivityAt = updatedAt,
        )

    private fun Row.toDetail(): ListDetail =
        ListDetail(
            id = id,
            name = name,
            icon = icon,
            color = color,
            isStarred = isStarred,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}
