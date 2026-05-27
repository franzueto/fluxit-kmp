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
 * Typed polymorphic owner — wraps the raw (`owner_type`, `owner_id`) pair
 * from the reminder row. The data layer (anticipated by Phase 04 §3)
 * trades [ReminderOwnerType] + raw String for this sum so use cases work
 * with type-safe ids instead of strings.
 *
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

/**
 * Caller-supplied fields for scheduling a new reminder. The repository
 * mints the id, sets `is_active = true`, and leaves `platformHandle` null
 * until the platform layer (Phase 06) binds the WorkManager / UNUserNotification
 * request id via [dev.franzueto.fluxit.shared.domain.repository.RemindersRepository.rebindPlatformHandle].
 */
public data class ReminderSpec(
    val owner: ReminderOwner,
    val firesAt: Instant,
    val recurrence: RecurrenceRule = RecurrenceRule.None,
)
