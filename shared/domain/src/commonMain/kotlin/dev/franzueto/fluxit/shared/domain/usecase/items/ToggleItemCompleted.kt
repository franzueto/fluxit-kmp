package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository
import kotlinx.coroutines.flow.first

/**
 * Reads the current item via [ItemsRepository.observe]`.first()`; a missing
 * or tombstoned id (the flow emits `null`) yields [DomainError.NotFound]
 * directly — the entity didn't resolve, which is a use-case-edge outcome,
 * not a storage error routed through `toDomain`. Repository write failures
 * still take the standard `toDomain(entity = "Item")` lift.
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
