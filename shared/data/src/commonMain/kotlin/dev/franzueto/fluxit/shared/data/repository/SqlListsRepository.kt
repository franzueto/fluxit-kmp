package dev.franzueto.fluxit.shared.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import dev.franzueto.fluxit.core.utils.IdGenerator
import dev.franzueto.fluxit.shared.data.db.FluxItDatabase
import dev.franzueto.fluxit.shared.data.mapper.toDetail
import dev.franzueto.fluxit.shared.data.mapper.toSummary
import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ListDetail
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.ListSummary
import dev.franzueto.fluxit.shared.domain.repository.ListsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

/**
 * SQLDelight-backed [ListsRepository] (Phase 03 §5). Pure orchestration:
 * every storage write maps to one `.sq` query; the only Kotlin-side logic
 * is the §8 sort-order minting and rebalance.
 *
 * Dispatcher injection lets tests pin Flow emissions to an unconfined
 * dispatcher; production binds [Dispatchers.Default]. We deliberately do
 * NOT use [Dispatchers.IO] (DoD: §1 — repositories stay off the IO pool;
 * SQLDelight calls are short and synchronous under the driver lock).
 */
public class SqlListsRepository(
    private val database: FluxItDatabase,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.System,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ListsRepository {
    private val queries get() = database.listsQueries

    override fun observeAll(): Flow<List<ListSummary>> =
        queries
            .selectWithCounts()
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toSummary() } }

    override fun observe(id: ListId): Flow<ListDetail?> =
        queries
            .selectById(id.value)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map { it?.toDetail() }

    override fun search(query: String): Flow<List<ListSummary>> =
        // Search reuses the dashboard projection (selectWithCounts) by
        // filtering the base list rows first. selectWithCounts already
        // joins items, so we re-route through searchByName for the
        // membership check and look up counts row-by-row would be N+1 —
        // instead just project the bare rows to summaries with zeroed
        // counters; the dashboard query stays the canonical counts source.
        // Phase 08's list-detail will swap to a dedicated query if/when
        // search needs counts in-context.
        queries
            .searchByName(query)
            .asFlow()
            .mapToList(dispatcher)
            .map { rows ->
                rows.map { row ->
                    ListSummary(
                        id = ListId(row.id),
                        name = row.name,
                        icon = row.icon,
                        color = row.color,
                        isStarred = row.is_starred,
                        totalItems = 0,
                        completedItems = 0,
                        lastActivityAt = row.updated_at,
                    )
                }
            }

    override suspend fun create(draft: ListDraft): Outcome<ListId, DataError> =
        guard {
            if (draft.name.isBlank()) {
                return Outcome.Err(DataError.Validation("name", "must not be blank"))
            }
            val now = clock.now()
            val id = ListId(ids.newId())
            // Newest-at-top (§12 row 5): mint below the current minimum so
            // ORDER BY sort_order ASC surfaces the new row first.
            val minSort = queries.selectMinActiveSortOrder().executeAsOne().min_sort_order
            val sortOrder = minSort?.let { it - 1.0 } ?: SEED_SORT_ORDER
            queries.insert(
                id = id.value,
                name = draft.name,
                icon = draft.icon,
                color = draft.color,
                is_starred = draft.isStarred,
                sort_order = sortOrder,
                created_at = now,
                updated_at = now,
            )
            Outcome.Ok(id)
        }

    override suspend fun rename(
        id: ListId,
        name: String,
    ): Outcome<Unit, DataError> =
        guard {
            if (name.isBlank()) {
                return Outcome.Err(DataError.Validation("name", "must not be blank"))
            }
            queries.updateName(name = name, updated_at = clock.now(), id = id.value)
            requireExists(id)
        }

    override suspend fun updateAppearance(
        id: ListId,
        icon: FluxItIconRef,
        color: ColorToken,
    ): Outcome<Unit, DataError> =
        guard {
            queries.updateAppearance(
                icon = icon,
                color = color,
                updated_at = clock.now(),
                id = id.value,
            )
            requireExists(id)
        }

    override suspend fun setStarred(
        id: ListId,
        starred: Boolean,
    ): Outcome<Unit, DataError> =
        guard {
            queries.updateStarred(is_starred = starred, updated_at = clock.now(), id = id.value)
            requireExists(id)
        }

    override suspend fun reorder(
        id: ListId,
        previous: ListId?,
        next: ListId?,
    ): Outcome<Unit, DataError> =
        guard {
            // Pull both bracket sort_orders; either may be null for endpoints
            // or for ids that no longer exist (treat as endpoint — caller is
            // responsible for stale-id semantics).
            val prevSort = previous?.let { queries.selectActiveSortOrder(it.value).executeAsOneOrNull() }
            val nextSort = next?.let { queries.selectActiveSortOrder(it.value).executeAsOneOrNull() }
            val target = computeTarget(prevSort, nextSort)
            val resolved =
                if (target.needsRebalance) {
                    rebalanceSortOrders()
                    val pSort = previous?.let { queries.selectActiveSortOrder(it.value).executeAsOneOrNull() }
                    val nSort = next?.let { queries.selectActiveSortOrder(it.value).executeAsOneOrNull() }
                    computeTarget(pSort, nSort).value
                } else {
                    target.value
                }
            queries.updateSortOrder(sort_order = resolved, updated_at = clock.now(), id = id.value)
            requireExists(id)
        }

    override suspend fun delete(id: ListId): Outcome<Unit, DataError> =
        guard {
            database.transactionWithResult {
                if (queries.selectById(id.value).executeAsOneOrNull() == null) {
                    Outcome.Err(DataError.NotFound(id.value))
                } else {
                    queries.softDelete(deleted_at = clock.now(), id = id.value)
                    Outcome.Ok(Unit)
                }
            }
        }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun requireExists(id: ListId): Outcome<Unit, DataError> =
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

    private fun rebalanceSortOrders() {
        database.transaction {
            val now = clock.now()
            val ids = queries.selectActiveIdsBySortOrder().executeAsList()
            ids.forEachIndexed { index, rowId ->
                queries.updateSortOrder(
                    sort_order = (index + 1).toDouble(),
                    updated_at = now,
                    id = rowId,
                )
            }
        }
    }

    /**
     * Wraps an operation that may throw at the SQLDelight boundary and
     * funnels failures into [DataError.Storage] so callers get the typed
     * outcome contract instead of a leaked driver exception.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> guard(block: () -> Outcome<T, DataError>): Outcome<T, DataError> =
        // SQLDelight wraps driver errors in unchecked subclasses of Exception
        // that aren't part of a stable public hierarchy — catching the broad
        // type is the only portable way to funnel them into DataError.Storage.
        // Coroutine cancellation propagates as a subclass of Exception too, so
        // we explicitly re-throw CancellationException to keep structured
        // concurrency intact.
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
