package dev.franzueto.fluxit.shared.domain.usecase.reminders

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.model.ReminderSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

class ObserveRemindersTest {
    private val itemId = ItemId("item-00000001")

    @Test
    fun observe_for_list_returns_only_that_list_owners_reminders() =
        runTest {
            val repo = newRemindersRepo()
            val listReminder = (repo.schedule(listReminderSpec()) as Outcome.Ok).value
            // An item reminder must not leak into the list feed.
            repo.schedule(
                ReminderSpec(owner = ReminderOwner.OfItem(itemId), firesAt = FIXED_NOW.plus(1.hours)),
            )

            val observed = ObserveRemindersForList(repo)(sampleListId).first()
            assertEquals(listOf(listReminder), observed.map { it.id })
        }

    @Test
    fun observe_for_item_returns_only_that_item_owners_reminders() =
        runTest {
            val repo = newRemindersRepo()
            val itemReminder =
                (
                    repo.schedule(
                        ReminderSpec(owner = ReminderOwner.OfItem(itemId), firesAt = FIXED_NOW.plus(1.hours)),
                    ) as Outcome.Ok
                ).value
            repo.schedule(listReminderSpec())

            val observed = ObserveRemindersForItem(repo)(itemId).first()
            assertEquals(listOf(itemReminder), observed.map { it.id })
        }
}
