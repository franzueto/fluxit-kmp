package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.model.Item
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository
import kotlinx.coroutines.flow.Flow

/**
 * Emits `null` for a missing or soft-deleted (tombstoned) item — the state
 * layer renders that "item is gone" case (e.g. after a delete elsewhere).
 *
 * Reactive read → returns [Flow], not `Outcome` (see [ObserveLists]).
 */
public class ObserveItem(
    private val items: ItemsRepository,
) {
    public operator fun invoke(itemId: ItemId): Flow<Item?> = items.observe(itemId)
}
