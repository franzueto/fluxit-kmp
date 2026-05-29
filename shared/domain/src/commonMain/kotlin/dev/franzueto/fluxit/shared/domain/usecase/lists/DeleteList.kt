package dev.franzueto.fluxit.shared.domain.usecase.lists

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.map
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.DeletedListSummary
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.repository.ListsRepository
import dev.franzueto.fluxit.shared.domain.repository.RemindersRepository
import dev.franzueto.fluxit.shared.domain.usecase.reminders.CancelReminder
import kotlinx.coroutines.flow.first

/**
 * Soft-delete a list and cancel all of its active reminders (Phase 04 §7),
 * returning a [DeletedListSummary] so the state layer can stage an undo
 * snackbar.
 *
 * Order of operations:
 * 1. Read the list header via `observe(id).first()` — a missing/tombstoned
 *    id is [DomainError.NotFound] (entity "List"), so the summary always
 *    reflects a list that really existed.
 * 2. Cancel every active reminder owned by the list, delegating to
 *    [CancelReminder] (OS disarm first, then DB tombstone). A
 *    [DomainError.SchedulerFailure] from any cancel aborts **before** the
 *    list is deleted, so the operation is retryable rather than leaving a
 *    deleted list with live OS notifications.
 * 3. Soft-delete the list row.
 *
 * **Cascade (ADR-006b):** items are *not* explicitly tombstoned here — the
 * list tombstone hides them from every observer, and cascade across
 * Lists → Items is the application-layer concern the ADR assigns to the data
 * layer's FK behaviour, not this use case. Reminders **are** cancelled
 * explicitly because they have OS-level side effects (a pending notification)
 * that a tombstone alone wouldn't disarm.
 *
 * **`UndoDeleteList` is not built** — it needs a data-layer restore primitive
 * (`deleted_at = NULL`) the shipped `ListsRepository` doesn't expose. The
 * returned [DeletedListSummary.cancelledReminderIds] are captured so undo can
 * reschedule once that primitive lands.
 */
public class DeleteList(
    private val lists: ListsRepository,
    private val reminders: RemindersRepository,
    private val cancelReminder: CancelReminder,
) {
    public suspend operator fun invoke(id: ListId): Outcome<DeletedListSummary, DomainError> {
        val detail =
            lists.observe(id).first()
                ?: return Outcome.Err(DomainError.NotFound(entity = "List", id = id.value))

        val owner = ReminderOwner.OfList(id)
        val active = reminders.observeForOwner(owner).first()
        for (reminder in active) {
            when (val cancelled = cancelReminder(owner, reminder.id)) {
                is Outcome.Err -> return cancelled
                is Outcome.Ok -> Unit
            }
        }

        return lists
            .delete(id)
            .mapError { it.toDomain(entity = "List") }
            .map {
                DeletedListSummary(
                    id = id,
                    name = detail.name,
                    cancelledReminderIds = active.map { it.id },
                )
            }
    }
}
