package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository

public class ClearCompletedItems(
    private val items: ItemsRepository,
) {
    public suspend operator fun invoke(listId: ListId): Outcome<Int, DomainError> =
        items.clearCompleted(listId).mapError { it.toDomain(entity = "Item") }
}
