package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.model.ItemsSection
import dev.franzueto.fluxit.shared.domain.model.ListDetail
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository
import dev.franzueto.fluxit.shared.domain.repository.ListsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Combined header + items projection backing the list-detail screen
 * (Phase 04 §7). Merges the list header stream
 * ([ListsRepository.observe]) with the partitioned items stream
 * ([ItemsRepository.observeByList]) into a single [ListDetailView] flow
 * via [combine], so the UI subscribes to one source of truth rather than
 * reconciling two flows itself.
 *
 * `detail` is nullable: a soft-deleted (or never-existed) list emits
 * `null` while the items stream emits an empty [ItemsSection] — the state
 * layer (Phase 05) decides how to render that "list is gone" case.
 *
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
