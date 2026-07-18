package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.model.ItemsSection
import dev.franzueto.fluxit.shared.domain.model.ListDetail
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository
import dev.franzueto.fluxit.shared.domain.repository.ListsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Reactive read → returns [Flow], not `Outcome` (see [ObserveLists]).
 */
public data class ListDetailView(
    val detail: ListDetail?,
    val items: ItemsSection,
)

public class ObserveListDetail(
    private val lists: ListsRepository,
    private val items: ItemsRepository,
) {
    public operator fun invoke(listId: ListId): Flow<ListDetailView> =
        combine(
            lists.observe(listId),
            items.observeByList(listId),
        ) { detail, section ->
            ListDetailView(detail = detail, items = section)
        }
}
