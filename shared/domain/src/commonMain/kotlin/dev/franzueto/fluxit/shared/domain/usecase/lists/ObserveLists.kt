package dev.franzueto.fluxit.shared.domain.usecase.lists

import dev.franzueto.fluxit.shared.domain.model.ListSummary
import dev.franzueto.fluxit.shared.domain.repository.ListsRepository
import kotlinx.coroutines.flow.Flow

/**
 * Dashboard feed use case (Phase 04 §7): every active list with its derived
 * counters, as a live [Flow]. A trivial delegate to
 * [ListsRepository.observeAll] — it exists so the state layer (Phase 05)
 * depends on a use-case surface rather than reaching into the repository
 * contract directly, keeping the inward arrow intact.
 *
 * Reactive reads return [Flow], not `Outcome` — the typed-error channel
 * (ADR-007) applies to command use cases; a subscription that can re-emit
 * has no single failure to fold. Errors on the read path surface as the
 * empty/stale emissions the repository already models.
 *
 * Shape per ADR-007b: a class with constructor-injected dependencies and a
 * single `operator fun invoke`.
 */
public class ObserveLists(
    private val lists: ListsRepository,
) {
    public operator fun invoke(): Flow<List<ListSummary>> = lists.observeAll()
}
