package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.model.Item
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository
import kotlinx.coroutines.flow.Flow

/**
 * Observe a single item by id as a live [Flow] (Phase 04 §7) — backs the
 * Edit-Item screen (Phase 10 / `plan/05` §4 `ItemDetailStore`). A trivial
 * delegate to [ItemsRepository.observe]; it exists so the state layer depends
 * on a use-case surface rather than reaching into the repository contract
 * directly, keeping the inward arrow intact (mirrors [ObserveLists] /
 * [ObserveListDetail]).
 *
 * Emits `null` for a missing or soft-deleted (tombstoned) item — the state
 * layer renders that "item is gone" case (e.g. after a delete elsewhere).
 *
 * Reactive read → returns [Flow], not `Outcome` (see [ObserveLists]).
 *
 * **Concurrency (§9):** caller dispatcher — any; returns a cold [Flow]
 * collected on the collector's dispatcher. No `shareIn`/`stateIn` here —
 * conflation/sharing is a state-layer choice (Phase 05).
 */
public class ObserveItem(
    private val items: ItemsRepository,
) {
    public operator fun invoke(itemId: ItemId): Flow<Item?> = items.observe(itemId)
}
