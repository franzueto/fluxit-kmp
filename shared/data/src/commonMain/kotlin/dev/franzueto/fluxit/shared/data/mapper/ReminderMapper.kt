package dev.franzueto.fluxit.shared.data.mapper

import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.Reminder
import dev.franzueto.fluxit.shared.domain.model.ReminderId
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.model.ReminderOwnerType
import dev.franzueto.fluxit.shared.data.db.Reminder as ReminderRow

internal fun ReminderRow.toDomain(): Reminder =
    Reminder(
        id = ReminderId(id),
        owner = ownerOf(owner_type, owner_id),
        firesAt = fires_at,
        // Storage stores None as NULL (§3 contract: "None ≡ null at the
        // storage edge"). Reinflate to the explicit sentinel so domain
        // code never has to special-case null vs. None.
        recurrence = recurrence ?: RecurrenceRule.None,
        platformHandle = platform_handle,
        isActive = is_active,
        createdAt = created_at,
        updatedAt = updated_at,
    )

private fun ownerOf(
    type: ReminderOwnerType,
    id: String,
): ReminderOwner =
    when (type) {
        ReminderOwnerType.LIST -> ReminderOwner.OfList(ListId(id))
        ReminderOwnerType.ITEM -> ReminderOwner.OfItem(ItemId(id))
    }
