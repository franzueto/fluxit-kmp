package dev.franzueto.fluxit.shared.domain.usecase.reminders

import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.Reminder
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.repository.RemindersRepository
import kotlinx.coroutines.flow.Flow

public class ObserveRemindersForItem(
    private val reminders: RemindersRepository,
) {
    public operator fun invoke(itemId: ItemId): Flow<List<Reminder>> = reminders.observeForOwner(ReminderOwner.OfItem(itemId))
}
