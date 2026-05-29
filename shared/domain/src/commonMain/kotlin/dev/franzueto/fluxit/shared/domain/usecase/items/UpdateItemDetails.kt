package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Optional
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.orElse
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ItemPatch
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.domain.model.TrimmedNonBlank
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository
import kotlinx.coroutines.flow.first

/**
 * Apply a partial edit to an existing item (Phase 04 §7) — backs the Edit
 * Item screen (title, subtitle, description, photo).
 *
 * This is the use case that **introduces [Optional]** (§6): each editable
 * field is supplied as an [Optional] so the caller distinguishes "leave it
 * alone" ([Optional.Unset]) from "set it" ([Optional.Set], including
 * `Set(null)` to clear a nullable field). The shipped [ItemsRepository.update]
 * contract takes a *full-replacement* [ItemPatch] (every column written
 * atomically), so this use case:
 *
 * 1. reads the current [item][dev.franzueto.fluxit.shared.domain.model.Item]
 *    via `observe(id).first()` — a missing/tombstoned id is
 *    [DomainError.NotFound] (entity "Item"), produced directly,
 * 2. validates `title` *only when supplied* via [TrimmedNonBlank.of]
 *    (blank → [DomainError.Validation], directly — title is non-nullable
 *    so `Unset` keeps the existing value and there's no way to clear it),
 * 3. folds each [Optional] over the current value via [orElse] into a
 *    complete [ItemPatch], then lifts the repo write via
 *    `toDomain(entity = "Item")`.
 *
 * Read-then-write isn't atomic; last-writer-wins through the observed flow
 * is acceptable for the single-user local store (§9). `subtitle` /
 * `description` are free-form and not validated.
 *
 *
 * **Concurrency (§9):** caller dispatcher — any; this use case does not block.
 * It suspends only on the injected repository/port, which owns its dispatcher;
 * the domain stays dispatcher-agnostic (no `withContext`/`Dispatchers.*`).
 */
public class UpdateItemDetails(
    private val items: ItemsRepository,
) {
    public suspend operator fun invoke(
        itemId: ItemId,
        title: Optional<String> = Optional.Unset,
        subtitle: Optional<String?> = Optional.Unset,
        description: Optional<String?> = Optional.Unset,
        photoId: Optional<PhotoId?> = Optional.Unset,
    ): Outcome<Unit, DomainError> {
        val current =
            items.observe(itemId).first()
                ?: return Outcome.Err(DomainError.NotFound(entity = "Item", id = itemId.value))

        val resolvedTitle: String =
            when (title) {
                is Optional.Unset -> current.title
                is Optional.Set ->
                    when (val validated = TrimmedNonBlank.of(title.value)) {
                        is Outcome.Err -> return Outcome.Err(DomainError.Validation(field = "title", rule = validated.error))
                        is Outcome.Ok -> validated.value.value
                    }
            }

        val patch =
            ItemPatch(
                title = resolvedTitle,
                subtitle = subtitle.orElse(current.subtitle),
                description = description.orElse(current.description),
                photoId = photoId.orElse(current.photoId),
            )
        return items.update(itemId, patch).mapError { it.toDomain(entity = "Item") }
    }
}
