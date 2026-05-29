package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository

/**
 * Toggle an item's starred flag (Phase 04 §7). Trivial delegate to
 * [ItemsRepository.setStarred] + the standard `toDomain(entity = "Item")`
 * lift (missing/tombstoned id → [DomainError.NotFound]). No input to
 * validate. Mirrors
 * [dev.franzueto.fluxit.shared.domain.usecase.lists.SetListStarred].
 */
public class SetItemStarred(
    private val items: ItemsRepository,
) {
    public suspend operator fun invoke(
        itemId: ItemId,
        starred: Boolean,
    ): Outcome<Unit, DomainError> = items.setStarred(itemId, starred).mapError { it.toDomain(entity = "Item") }
}
