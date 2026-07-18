package dev.franzueto.fluxit.shared.domain.usecase.lists

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.repository.ListsRepository

public class ReorderList(
    private val lists: ListsRepository,
) {
    public suspend operator fun invoke(
        id: ListId,
        previous: ListId?,
        next: ListId?,
    ): Outcome<Unit, DomainError> = lists.reorder(id, previous, next).mapError { it.toDomain(entity = "List") }
}
