package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository

public class SetItemStarred(
    private val items: ItemsRepository,
) {
    public suspend operator fun invoke(
        itemId: ItemId,
        starred: Boolean,
    ): Outcome<Unit, DomainError> = items.setStarred(itemId, starred).mapError { it.toDomain(entity = "Item") }
}
