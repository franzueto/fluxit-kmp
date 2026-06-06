package dev.franzueto.fluxit.shared.state.navigation

import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.state.store.ListDetailEffect
import dev.franzueto.fluxit.shared.state.store.ListsEffect
import dev.franzueto.fluxit.shared.state.store.RootEffect

/**
 * Swift-facing string accessors for navigation effects (Phase 07 Slice 7).
 *
 * Kotlin/Native erases the `@JvmInline value class` ids (`ListId`/`ItemId`) to an
 * opaque Obj-C `id` at the framework boundary, with no `.value` getter — so the
 * SwiftUI shell can't read the raw string it needs to build a `NavigationStack`
 * route payload from a navigation effect. These one-line extensions expose it.
 * (Intents still accept the boxed id directly, so no accessor is needed there.)
 */
public fun ListsEffect.NavigateToListDetail.listId(): String = id.value

public fun RootEffect.NavigateToList.listId(): String = id.value

public fun RootEffect.NavigateToItem.itemId(): String = id.value

public fun ListDetailEffect.NavigateToEditItem.itemId(): String = id.value

/**
 * Swift-callable [ListId] factory. The `@JvmInline value class` constructor isn't
 * surfaced for direct `ListId(value:)` use from Swift, so the list-detail screen
 * builds the id for `ListDetailIntent.Init` from its route-arg string here.
 */
public fun listIdOf(value: String): ListId = ListId(value)
