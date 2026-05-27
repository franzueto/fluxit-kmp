package dev.franzueto.fluxit.shared.domain.repository

import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ListDetail
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.ListSummary
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for FluxIt lists (Phase 03 §5). Reads return Flow so
 * UI can subscribe directly; writes return [Outcome] with typed [DataError]
 * so call sites pattern-match on failure variants rather than catching.
 *
 * Soft-delete semantics: every observer filters out tombstoned rows; the
 * data layer never surfaces a `deleted_at IS NOT NULL` row through this
 * interface.
 */
public interface ListsRepository {
    /** Dashboard feed: every active list with derived counters + activity. */
    public fun observeAll(): Flow<List<ListSummary>>

    /** Single-list header for the list-detail screen; emits null when soft-deleted. */
    public fun observe(id: ListId): Flow<ListDetail?>

    /**
     * Substring match on `name` (case-insensitive), lists-only per Phase 03
     * §12 row 2. Debounce/throttle is the state layer's responsibility.
     */
    public fun search(query: String): Flow<List<ListSummary>>

    public suspend fun create(draft: ListDraft): Outcome<ListId, DataError>

    public suspend fun rename(
        id: ListId,
        name: String,
    ): Outcome<Unit, DataError>

    public suspend fun updateAppearance(
        id: ListId,
        icon: FluxItIconRef,
        color: ColorToken,
    ): Outcome<Unit, DataError>

    public suspend fun setStarred(
        id: ListId,
        starred: Boolean,
    ): Outcome<Unit, DataError>

    /**
     * Reorder [id] to sit between [previous] and [next] (either may be null
     * for endpoints). Repository computes the fractional sort_order and
     * triggers a rebalance if the resulting gap collapses below precision.
     */
    public suspend fun reorder(
        id: ListId,
        previous: ListId?,
        next: ListId?,
    ): Outcome<Unit, DataError>

    public suspend fun delete(id: ListId): Outcome<Unit, DataError>
}
