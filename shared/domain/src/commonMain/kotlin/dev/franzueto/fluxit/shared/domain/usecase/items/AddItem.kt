package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.ItemDraft
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.TrimmedNonBlank
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository

/**
 * Append a new item to a list's active section (Phase 04 §7).
 *
 * Validates `draft.title` at the use-case edge via [TrimmedNonBlank.of]
 * (blank → [DomainError.Validation], directly) and persists the
 * *normalised* (trimmed) title. New items land in the TO BUY section
 * (`isCompleted = false`) at the tail — the repository owns the
 * sort_order + id + timestamps, same division of labour as
 * [dev.franzueto.fluxit.shared.domain.usecase.lists.CreateList].
 *
 * `subtitle` / `description` are free-form and not validated here.
 *
 *
 * **Concurrency (§9):** caller dispatcher — any; this use case does not block.
 * It suspends only on the injected repository/port, which owns its dispatcher;
 * the domain stays dispatcher-agnostic (no `withContext`/`Dispatchers.*`).
 */
public class AddItem(
    private val items: ItemsRepository,
) {
    public suspend operator fun invoke(
        listId: ListId,
        draft: ItemDraft,
    ): Outcome<ItemId, DomainError> =
        when (val title = TrimmedNonBlank.of(draft.title)) {
            is Outcome.Err -> Outcome.Err(DomainError.Validation(field = "title", rule = title.error))
            is Outcome.Ok ->
                items
                    .add(listId, draft.copy(title = title.value.value))
                    .mapError { it.toDomain(entity = "Item") }
        }
}
