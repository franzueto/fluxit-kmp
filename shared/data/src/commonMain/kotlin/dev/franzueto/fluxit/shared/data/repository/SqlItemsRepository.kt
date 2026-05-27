package dev.franzueto.fluxit.shared.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import dev.franzueto.fluxit.core.utils.IdGenerator
import dev.franzueto.fluxit.shared.data.db.FluxItDatabase
import dev.franzueto.fluxit.shared.data.mapper.toDomain
import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.Item
import dev.franzueto.fluxit.shared.domain.model.ItemDraft
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ItemPatch
import dev.franzueto.fluxit.shared.domain.model.ItemsSection
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

/**
 * SQLDelight-backed [ItemsRepository] (Phase 03 §5, 2/4). Same shape as
 * [SqlListsRepository]; the §8 sort-order rebalance is scoped to one
 * list at a time rather than the whole table.
 */
public class SqlItemsRepository(
    private val database: FluxItDatabase,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.System,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ItemsRepository {
    private val queries get() = database.itemsQueries

    override fun observeByList(listId: ListId): Flow<ItemsSection> =
        queries
            .selectByListGroupedByStatus(listId.value)
            .asFlow()
            .mapToList(dispatcher)
            .map { rows ->
                val items = rows.map { it.toDomain() }
                // SQL ORDER BY is_completed ASC, sort_order ASC → false-group
                // (active) first, then true-group (completed). Partition
                // preserves order within each side.
                val (completed, active) = items.partition { it.isCompleted }
                ItemsSection(
                    active = active,
                    completed = completed,
                    total = items.size,
                    completedCount = completed.size,
                )
            }

    override fun observe(itemId: ItemId): Flow<Item?> =
        queries
            .selectById(itemId.value)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map { it?.toDomain() }

    override suspend fun add(
        listId: ListId,
        draft: ItemDraft,
    ): Outcome<ItemId, DataError> =
        guard {
            if (draft.title.isBlank()) {
                return Outcome.Err(DataError.Validation("title", "must not be blank"))
            }
            database.transactionWithResult {
                // Pre-flight: parent list must exist (and not be tombstoned).
                // FK alone would surface a Storage error from the driver —
                // explicit lookup yields a typed NotFound instead.
                if (queries.selectListIsActive(listId.value).executeAsOneOrNull() == null) {
                    return@transactionWithResult Outcome.Err(DataError.NotFound(listId.value))
                }
                val now = clock.now()
                val id = ItemId(ids.newId())
                val minSort = queries.selectMinActiveSortOrderByList(listId.value).executeAsOne().min_sort_order
                val sortOrder = minSort?.let { it - 1.0 } ?: SEED_SORT_ORDER
                queries.insert(
                    id = id.value,
                    list_id = listId.value,
                    title = draft.title,
                    subtitle = draft.subtitle,
                    description = draft.description,
                    is_completed = false,
                    is_starred = draft.isStarred,
                    photo_id = draft.photoId?.value,
                    sort_order = sortOrder,
                    created_at = now,
                    updated_at = now,
                )
                Outcome.Ok(id)
            }
        }

    override suspend fun update(
        itemId: ItemId,
        patch: ItemPatch,
    ): Outcome<Unit, DataError> =
        guard {
            if (patch.title.isBlank()) {
                return Outcome.Err(DataError.Validation("title", "must not be blank"))
            }
            queries.updateContent(
                title = patch.title,
                subtitle = patch.subtitle,
                description = patch.description,
                photo_id = patch.photoId?.value,
                updated_at = clock.now(),
                id = itemId.value,
            )
            requireExists(itemId)
        }

    override suspend fun setCompleted(
        itemId: ItemId,
        completed: Boolean,
    ): Outcome<Unit, DataError> =
        guard {
            queries.setCompleted(is_completed = completed, updated_at = clock.now(), id = itemId.value)
            requireExists(itemId)
        }

    override suspend fun setStarred(
        itemId: ItemId,
        starred: Boolean,
    ): Outcome<Unit, DataError> =
        guard {
            queries.setStarred(is_starred = starred, updated_at = clock.now(), id = itemId.value)
            requireExists(itemId)
        }

    override suspend fun reorder(
        itemId: ItemId,
        previous: ItemId?,
        next: ItemId?,
    ): Outcome<Unit, DataError> =
        guard {
            val prevSort = previous?.let { queries.selectActiveSortOrder(it.value).executeAsOneOrNull() }
            val nextSort = next?.let { queries.selectActiveSortOrder(it.value).executeAsOneOrNull() }
            val target = computeTarget(prevSort, nextSort)
            val resolved =
                if (target.needsRebalance) {
                    // Caller is responsible for previous/next sharing the
                    // item's list — we look up the item's list_id here to
                    // scope the rebalance correctly.
                    val listId =
                        queries.selectById(itemId.value).executeAsOneOrNull()?.list_id
                            ?: return@guard Outcome.Err(DataError.NotFound(itemId.value))
                    rebalanceSortOrders(listId)
                    val pSort = previous?.let { queries.selectActiveSortOrder(it.value).executeAsOneOrNull() }
                    val nSort = next?.let { queries.selectActiveSortOrder(it.value).executeAsOneOrNull() }
                    computeTarget(pSort, nSort).value
                } else {
                    target.value
                }
            queries.updateSortOrder(sort_order = resolved, updated_at = clock.now(), id = itemId.value)
            requireExists(itemId)
        }

    override suspend fun delete(itemId: ItemId): Outcome<Unit, DataError> =
        guard {
            database.transactionWithResult {
                if (queries.selectById(itemId.value).executeAsOneOrNull() == null) {
                    Outcome.Err(DataError.NotFound(itemId.value))
                } else {
                    queries.softDelete(deleted_at = clock.now(), id = itemId.value)
                    Outcome.Ok(Unit)
                }
            }
        }

    override suspend fun clearCompleted(listId: ListId): Outcome<Int, DataError> =
        guard {
            database.transactionWithResult {
                val count = queries.countCompletedByList(listId.value).executeAsOne().toInt()
                if (count > 0) {
                    queries.softDeleteCompletedByList(deleted_at = clock.now(), list_id = listId.value)
                }
                Outcome.Ok(count)
            }
        }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun requireExists(id: ItemId): Outcome<Unit, DataError> =
        if (queries.selectById(id.value).executeAsOneOrNull() == null) {
            Outcome.Err(DataError.NotFound(id.value))
        } else {
            Outcome.Ok(Unit)
        }

    private data class Target(
        val value: Double,
        val needsRebalance: Boolean,
    )

    private fun computeTarget(
        prev: Double?,
        next: Double?,
    ): Target =
        when {
            prev == null && next == null -> Target(SEED_SORT_ORDER, needsRebalance = false)
            prev == null -> Target(next!! - 1.0, needsRebalance = false)
            next == null -> Target(prev + 1.0, needsRebalance = false)
            (next - prev) < REBALANCE_EPSILON -> Target((prev + next) / 2.0, needsRebalance = true)
            else -> Target((prev + next) / 2.0, needsRebalance = false)
        }

    private fun rebalanceSortOrders(listId: String) {
        database.transaction {
            val now = clock.now()
            val rowIds = queries.selectActiveIdsByListBySortOrder(listId).executeAsList()
            rowIds.forEachIndexed { index, rowId ->
                queries.updateSortOrder(
                    sort_order = (index + 1).toDouble(),
                    updated_at = now,
                    id = rowId,
                )
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> guard(block: () -> Outcome<T, DataError>): Outcome<T, DataError> =
        // See SqlListsRepository.guard — SQLDelight's exception hierarchy
        // isn't a stable public surface; CancellationException is re-thrown
        // to keep structured concurrency intact.
        try {
            block()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Outcome.Err(DataError.Storage(e))
        }

    private companion object {
        const val SEED_SORT_ORDER: Double = 1.0
        const val REBALANCE_EPSILON: Double = 1e-9
    }
}
