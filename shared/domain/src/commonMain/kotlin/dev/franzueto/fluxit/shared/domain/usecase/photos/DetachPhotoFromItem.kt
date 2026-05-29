package dev.franzueto.fluxit.shared.domain.usecase.photos

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Optional
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.repository.ItemsRepository
import dev.franzueto.fluxit.shared.domain.usecase.items.UpdateItemDetails
import kotlinx.coroutines.flow.first

/**
 * Clear an item's photo and reclaim the file if it's now orphaned
 * (Phase 04 §7).
 *
 * 1. Read the item via `observe(id).first()` — a missing/tombstoned item is
 *    [DomainError.NotFound]; an item with no photo is a no-op `Ok`.
 * 2. Clear `photo_id` by composing [UpdateItemDetails] with
 *    `photoId = Optional.Set(null)`.
 * 3. Hand the now-detached photo to [PhotoJanitor], which GCs the row+file
 *    iff no other live item still references it (so a photo shared across
 *    items survives).
 *
 * The §7 row said detach "schedules `PhotoJanitor` to GC the file later"; we
 * run the janitor inline (single-user local store, cheap) rather than queuing
 * — the janitor is itself a no-op when the photo is still referenced.
 *
 *
 * **Concurrency (§9):** caller dispatcher — any; this use case does not block.
 * It suspends only on the injected repository/port, which owns its dispatcher;
 * the domain stays dispatcher-agnostic (no `withContext`/`Dispatchers.*`).
 */
public class DetachPhotoFromItem(
    private val items: ItemsRepository,
    private val updateItemDetails: UpdateItemDetails,
    private val photoJanitor: PhotoJanitor,
) {
    public suspend operator fun invoke(itemId: ItemId): Outcome<Unit, DomainError> {
        val item =
            items.observe(itemId).first()
                ?: return Outcome.Err(DomainError.NotFound(entity = "Item", id = itemId.value))

        val detached = item.photoId ?: return Outcome.Ok(Unit) // nothing to detach

        when (val cleared = updateItemDetails(itemId, photoId = Optional.Set(null))) {
            is Outcome.Err -> return cleared
            is Outcome.Ok -> Unit
        }

        return when (val reclaimed = photoJanitor(detached)) {
            is Outcome.Err -> reclaimed
            is Outcome.Ok -> Outcome.Ok(Unit)
        }
    }
}
