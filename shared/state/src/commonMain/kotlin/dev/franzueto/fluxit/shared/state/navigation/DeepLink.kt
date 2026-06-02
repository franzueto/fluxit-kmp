package dev.franzueto.fluxit.shared.state.navigation

import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ListId

/**
 * A parsed `fluxit://` deep link (plan/06 §5, plan/07 §1).
 *
 * The two shapes match the reminder-notification payloads minted by
 * `platform-reminders` (`ScheduledNotification.deepLink`): `fluxit://list/{id}`
 * and `fluxit://item/{id}`. [parse] is **pure** — no IO, no platform calls — so
 * it's exhaustively unit-tested at the `:shared:state` ≥90% Kover gate, and the
 * app shells (Android intent filter, iOS `.onOpenURL`) just hand the raw string
 * to [RootStore][dev.franzueto.fluxit.shared.state.store.RootStore].
 */
public sealed interface DeepLink {
    public data class List(
        val id: ListId,
    ) : DeepLink

    public data class Item(
        val id: ItemId,
    ) : DeepLink

    public companion object {
        private const val SCHEME = "fluxit://"
        private const val HOST_LIST = "list"
        private const val HOST_ITEM = "item"

        /**
         * Parse [url] into a [DeepLink], or `null` if it isn't a well-formed
         * `fluxit://list/{id}` / `fluxit://item/{id}` URL. Unknown schemes,
         * unknown hosts, missing/blank ids, and extra path segments all yield
         * `null` — callers treat that as "ignore, stay where you are".
         */
        public fun parse(url: String): DeepLink? {
            if (!url.startsWith(SCHEME)) return null
            val parts = url.removePrefix(SCHEME).split('/')
            if (parts.size != 2) return null
            val (host, rawId) = parts
            if (rawId.isBlank()) return null
            return when (host) {
                HOST_LIST -> List(ListId(rawId))
                HOST_ITEM -> Item(ItemId(rawId))
                else -> null
            }
        }
    }
}
