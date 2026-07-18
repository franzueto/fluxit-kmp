package dev.franzueto.fluxit.shared.domain.repository

import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.Item
import dev.franzueto.fluxit.shared.domain.model.ItemDraft
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ItemPatch
import dev.franzueto.fluxit.shared.domain.model.ItemsSection
import dev.franzueto.fluxit.shared.domain.model.ListId
import kotlinx.coroutines.flow.Flow

public interface ItemsRepository {
    /**
     * Backs the list-detail screen. Single SQL projection partitioned
     * into active/completed so both sections + the rollup counters stay
     * consistent within one Flow emission.
     */
    public fun observeByList(listId: ListId): Flow<ItemsSection>

    public fun observe(itemId: ItemId): Flow<Item?>

    public suspend fun add(
        listId: ListId,
        draft: ItemDraft,
    ): Outcome<ItemId, DataError>

    public suspend fun update(
        itemId: ItemId,
        patch: ItemPatch,
    ): Outcome<Unit, DataError>

    public suspend fun setCompleted(
        itemId: ItemId,
        completed: Boolean,
    ): Outcome<Unit, DataError>

    public suspend fun setStarred(
        itemId: ItemId,
        starred: Boolean,
    ): Outcome<Unit, DataError>

    public suspend fun reorder(
        itemId: ItemId,
        previous: ItemId?,
        next: ItemId?,
    ): Outcome<Unit, DataError>

    public suspend fun delete(itemId: ItemId): Outcome<Unit, DataError>

    public suspend fun clearCompleted(listId: ListId): Outcome<Int, DataError>
}
