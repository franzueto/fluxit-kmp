package dev.franzueto.fluxit.shared.domain.usecase.lists

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.TrimmedNonBlank
import dev.franzueto.fluxit.shared.domain.repository.ListsRepository

/**
 * Create a new list from a caller-supplied [ListDraft] (Phase 04 §7).
 *
 * Validates `draft.name` at the use-case edge via [TrimmedNonBlank.of] and
 * persists the *normalised* (trimmed) name so leading/trailing whitespace
 * never reaches storage. A blank name yields [DomainError.Validation]
 * directly — it never travels through the repository / [DataError] channel.
 *
 * **Spec/reality reconciliation:** the §7 punch list says "mint id via
 * `IdGenerator` → `repo.create`", but the shipped [ListsRepository.create]
 * contract mints the id itself (so the data layer owns sort_order +
 * timestamps + id atomically) and returns the fresh [ListId]. This use case
 * therefore delegates id-minting to the repository and carries no
 * `IdGenerator` dependency — the id seam lives one layer down, where the row
 * is actually written.
 *
 * **Analytics deferred:** a `list_created` emission belongs here once §5's
 * `AnalyticsSink` port lands; wire it in then.
 */
public class CreateList(
    private val lists: ListsRepository,
) {
    public suspend operator fun invoke(draft: ListDraft): Outcome<ListId, DomainError> =
        when (val name = TrimmedNonBlank.of(draft.name)) {
            is Outcome.Err -> Outcome.Err(DomainError.Validation(field = "name", rule = name.error))
            is Outcome.Ok ->
                lists
                    .create(draft.copy(name = name.value.value))
                    .mapError { it.toDomain(entity = "List") }
        }
}
