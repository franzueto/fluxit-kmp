package dev.franzueto.fluxit.shared.domain.usecase.reminders

import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.Reminder
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.repository.RemindersRepository
import kotlinx.coroutines.flow.Flow

/**
 * Observe the active reminders attached to a list (Phase 04 §7). Reactive
 * read → returns [Flow], not `Outcome`; a trivial delegate that wraps the
 * [ListId] in [ReminderOwner.OfList] for [RemindersRepository.observeForOwner].
 */
public class ObserveRemindersForList(
    private val reminders: RemindersRepository,
) {
    public operator fun invoke(listId: ListId): Flow<List<Reminder>> = reminders.observeForOwner(ReminderOwner.OfList(listId))
}
