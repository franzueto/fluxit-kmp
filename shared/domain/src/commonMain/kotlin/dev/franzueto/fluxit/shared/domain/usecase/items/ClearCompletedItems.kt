package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository

/**
 * Bulk soft-delete every completed item in a list (Phase 04 §7). Delegate
 * to [ItemsRepository.clearCompleted] + the standard
 * `toDomain(entity = "Item")` lift. Returns the **count** cleared so the
 * state layer can stage a "cleared N items" toast.
 *
 * **Spec/reality reconciliation:** the §7 punch list specifies a
 * `List<ItemId>` return (the deleted ids, to back a single bulk-undo
 * snackbar via a companion `RestoreItems`). The shipped repository contract
 * returns an `Int` count and has no restore primitive, so this use case
 * returns `Outcome<Int, DomainError>` for now. The id-returning variant +
 * `RestoreItems` land together once Phase 03's data layer surfaces the
 * `RETURNING id` rows + a bulk-restore method.
 */
public class ClearCompletedItems(
    private val items: ItemsRepository,
) {
    public suspend operator fun invoke(listId: ListId): Outcome<Int, DomainError> =
        items.clearCompleted(listId).mapError { it.toDomain(entity = "Item") }
}
