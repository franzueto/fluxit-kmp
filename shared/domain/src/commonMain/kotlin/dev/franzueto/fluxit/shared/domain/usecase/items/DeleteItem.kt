package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository

/**
 * Soft-delete a single item (Phase 04 §7). Delegate to
 * [ItemsRepository.delete] + the standard `toDomain(entity = "Item")` lift.
 *
 * **`UndoDeleteItem` is deferred:** the shipped [ItemsRepository] contract
 * has no restore primitive (no `deleted_at = NULL` write), so undo can't be
 * built in the domain layer yet — it lands once Phase 03's data layer
 * surfaces a restore/bulk-restore method (tracked alongside the
 * `ClearCompletedItems → List<ItemId>` + `RestoreItems` bulk-undo wave).
 *
 *
 * **Concurrency (§9):** caller dispatcher — any; this use case does not block.
 * It suspends only on the injected repository/port, which owns its dispatcher;
 * the domain stays dispatcher-agnostic (no `withContext`/`Dispatchers.*`).
 */
public class DeleteItem(
    private val items: ItemsRepository,
) {
    public suspend operator fun invoke(itemId: ItemId): Outcome<Unit, DomainError> =
        items.delete(itemId).mapError { it.toDomain(entity = "Item") }
}
