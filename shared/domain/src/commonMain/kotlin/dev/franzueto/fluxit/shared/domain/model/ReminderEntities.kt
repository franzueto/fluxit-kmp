package dev.franzueto.fluxit.shared.domain.model

import kotlinx.datetime.Instant
import kotlin.jvm.JvmInline

@JvmInline
public value class ReminderId(
    public val value: String,
) {
    init {
        require(value.isNotEmpty()) { "ReminderId must not be empty" }
    }
}

/**
 * [type] / [id] are the storage projection helpers used by the data
 * layer to write the row; UI / use-case code should always pattern-match
 * the variant.
 */
public sealed interface ReminderOwner {
    public val type: ReminderOwnerType
    public val id: String

    public data class OfList(
        val listId: ListId,
    ) : ReminderOwner {
        override val type: ReminderOwnerType get() = ReminderOwnerType.LIST
        override val id: String get() = listId.value
    }

    public data class OfItem(
        val itemId: ItemId,
    ) : ReminderOwner {
        override val type: ReminderOwnerType get() = ReminderOwnerType.ITEM
        override val id: String get() = itemId.value
    }
}

/** Single reminder row. `recurrence` defaults to [RecurrenceRule.None] for one-shots. */
public data class Reminder(
    val id: ReminderId,
    val owner: ReminderOwner,
    val firesAt: Instant,
    val recurrence: RecurrenceRule,
    val platformHandle: String?,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

public data class ReminderSpec(
    val owner: ReminderOwner,
    val firesAt: Instant,
    val recurrence: RecurrenceRule = RecurrenceRule.None,
)
