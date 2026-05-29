package dev.franzueto.fluxit.shared.domain.usecase.lists

import dev.franzueto.fluxit.shared.domain.model.ListSummary
import dev.franzueto.fluxit.shared.domain.repository.ListsRepository
import kotlinx.coroutines.flow.Flow

/**
 * Substring search over active lists (Phase 04 §7). Normalises the raw query
 * (trim + lowercase) and delegates to [ListsRepository.search].
 *
 * Validator-discipline pattern: an empty/blank query is NOT an error — it
 * trims to `""`, which the repository contract treats as "match everything",
 * so the dashboard falls back to the full feed. No exception, no `Outcome`
 * failure channel; the empty case is a legitimate result, not a rejection.
 *
 * Returns [Flow] for the same reason as [ObserveLists]: reactive reads have
 * no single fold-able failure.
 */
public class SearchLists(
    private val lists: ListsRepository,
) {
    public operator fun invoke(query: String): Flow<List<ListSummary>> = lists.search(query.trim().lowercase())
}
