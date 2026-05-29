package dev.franzueto.fluxit.shared.domain.usecase.lists

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.repository.ListsRepository

/**
 * Toggle a list's starred flag (Phase 04 §7). A trivial delegate to
 * [ListsRepository.setStarred] with the standard repository-error lift —
 * a write against a missing/tombstoned id surfaces as
 * [DomainError.NotFound] with the "List" entity label. No input to
 * validate: [starred] is a `Boolean`, the id is a typed value class.
 */
public class SetListStarred(
    private val lists: ListsRepository,
) {
    public suspend operator fun invoke(
        id: ListId,
        starred: Boolean,
    ): Outcome<Unit, DomainError> = lists.setStarred(id, starred).mapError { it.toDomain(entity = "List") }
}
