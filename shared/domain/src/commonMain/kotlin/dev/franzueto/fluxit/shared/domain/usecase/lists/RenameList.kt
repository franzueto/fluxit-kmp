package dev.franzueto.fluxit.shared.domain.usecase.lists

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.TrimmedNonBlank
import dev.franzueto.fluxit.shared.domain.repository.ListsRepository

/**
 * Rename an existing list (Phase 04 §7).
 *
 * Validates the new name at the use-case edge via [TrimmedNonBlank.of]
 * (blank → [DomainError.Validation], directly), persists the trimmed value,
 * and lifts any repository [DataError] into the domain channel via
 * `mapError { it.toDomain(entity = "List") }` — so a write against a
 * missing/tombstoned id surfaces as [DomainError.NotFound] with the "List"
 * entity label.
 */
public class RenameList(
    private val lists: ListsRepository,
) {
    public suspend operator fun invoke(
        id: ListId,
        name: String,
    ): Outcome<Unit, DomainError> =
        when (val valid = TrimmedNonBlank.of(name)) {
            is Outcome.Err -> Outcome.Err(DomainError.Validation(field = "name", rule = valid.error))
            is Outcome.Ok ->
                lists
                    .rename(id, valid.value.value)
                    .mapError { it.toDomain(entity = "List") }
        }
}
