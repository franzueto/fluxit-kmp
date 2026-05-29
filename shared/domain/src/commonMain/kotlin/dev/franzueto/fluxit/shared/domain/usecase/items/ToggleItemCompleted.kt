package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository
import kotlinx.coroutines.flow.first

/**
 * Flip an item's completed flag (Phase 04 §7). A single use case rather
 * than separate `Complete` / `Uncomplete` — it reads the current state and
 * writes its negation.
 *
 * Reads the current item via [ItemsRepository.observe]`.first()`; a missing
 * or tombstoned id (the flow emits `null`) yields [DomainError.NotFound]
 * directly — the entity didn't resolve, which is a use-case-edge outcome,
 * not a storage error routed through `toDomain`. Repository write failures
 * still take the standard `toDomain(entity = "Item")` lift.
 *
 * Note the read-then-write is not atomic; for a single-user local store the
 * last-writer-wins reconciliation through the observed flow is acceptable
 * (the §9 concurrency contract keeps domain dispatcher-agnostic).
 */
public class ToggleItemCompleted(
    private val items: ItemsRepository,
) {
    public suspend operator fun invoke(itemId: ItemId): Outcome<Unit, DomainError> {
        val current =
            items.observe(itemId).first()
                ?: return Outcome.Err(DomainError.NotFound(entity = "Item", id = itemId.value))
        return items
            .setCompleted(itemId, !current.isCompleted)
            .mapError { it.toDomain(entity = "Item") }
    }
}
