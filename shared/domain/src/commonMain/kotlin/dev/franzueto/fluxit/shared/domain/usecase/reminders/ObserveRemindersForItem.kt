package dev.franzueto.fluxit.shared.domain.usecase.reminders

import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.Reminder
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.repository.RemindersRepository
import kotlinx.coroutines.flow.Flow

/**
 * Observe the active reminders attached to an item (Phase 04 §7). Reactive
 * read → returns [Flow], not `Outcome`; a trivial delegate that wraps the
 * [ItemId] in [ReminderOwner.OfItem] for [RemindersRepository.observeForOwner].
 *
 *
 * **Concurrency (§9):** caller dispatcher — any; returns a cold [Flow]
 * collected on the collector's dispatcher. No `shareIn`/`stateIn` here —
 * conflation/sharing is a state-layer choice (Phase 05).
 */
public class ObserveRemindersForItem(
    private val reminders: RemindersRepository,
) {
    public operator fun invoke(itemId: ItemId): Flow<List<Reminder>> = reminders.observeForOwner(ReminderOwner.OfItem(itemId))
}
