package dev.franzueto.fluxit.shared.domain.model

/**
 * Discriminator for [reminder.owner_id] — whether a given reminder belongs
 * to a list or to an individual item. The data layer's `OwnerTypeAdapter`
 * round-trips this enum to the `reminder.owner_type` TEXT column.
 */
public enum class ReminderOwnerType {
    LIST,
    ITEM,
}
