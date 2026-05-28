package dev.franzueto.fluxit.shared.domain.model

/**
 * Discriminator for [reminder.owner_id] — whether a given reminder belongs
 * to a list or to an individual item. The data layer's `OwnerTypeAdapter`
 * round-trips this enum to the `reminder.owner_type` TEXT column.
 *
 * Phase 04 §3 wraps owner_type + owner_id together as a sealed
 * `ReminderOwner { List(ListId); Item(ItemId) }` at the use-case boundary;
 * this enum is the storage-boundary representation only. The repository
 * splits and joins the two when crossing between domain and SQL.
 */
public enum class ReminderOwnerType {
    LIST,
    ITEM,
}
