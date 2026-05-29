package dev.franzueto.fluxit.shared.domain.usecase.lists

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.repository.ListsRepository

/**
 * Move a list to sit between two neighbours on the dashboard (Phase 04 §7).
 * Input is the `(movedId, previous?, next?)` bracket; the repository computes
 * the fractional sort_order (via `SortOrderArithmetic`) and rebalances if the
 * gap collapses — same division of labour as [ReorderItem], so this use case
 * is a delegate + the standard `toDomain(entity = "List")` lift. Either
 * neighbour may be `null` for the dashboard endpoints.
 *
 *
 * **Concurrency (§9):** caller dispatcher — any; this use case does not block.
 * It suspends only on the injected repository/port, which owns its dispatcher;
 * the domain stays dispatcher-agnostic (no `withContext`/`Dispatchers.*`).
 */
public class ReorderList(
    private val lists: ListsRepository,
) {
    public suspend operator fun invoke(
        id: ListId,
        previous: ListId?,
        next: ListId?,
    ): Outcome<Unit, DomainError> = lists.reorder(id, previous, next).mapError { it.toDomain(entity = "List") }
}
