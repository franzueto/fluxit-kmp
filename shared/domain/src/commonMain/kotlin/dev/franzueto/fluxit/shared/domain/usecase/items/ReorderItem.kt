package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository

/**
 * Move an item to sit between two neighbours (Phase 04 §7). Input is the
 * `(movedId, previous?, next?)` bracket; the repository computes the
 * fractional sort_order (via `SortOrderArithmetic`) and rebalances if the
 * gap collapses — same division of labour as the Lists reorder path, so
 * this use case is a delegate + the standard `toDomain(entity = "Item")`
 * lift. Either neighbour may be `null` for the list endpoints.
 */
public class ReorderItem(
    private val items: ItemsRepository,
) {
    public suspend operator fun invoke(
        itemId: ItemId,
        previous: ItemId?,
        next: ItemId?,
    ): Outcome<Unit, DomainError> = items.reorder(itemId, previous, next).mapError { it.toDomain(entity = "Item") }
}
