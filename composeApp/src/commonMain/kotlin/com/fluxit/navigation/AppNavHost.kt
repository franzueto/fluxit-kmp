package com.fluxit.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.fluxit.feature.createlist.CreateListScreen
import com.fluxit.feature.dashboard.DashboardScreen
import com.fluxit.feature.itemdetail.ItemDetailScreen
import com.fluxit.feature.listdetail.ListDetailScreen
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Serializable
sealed interface AppRoute : NavKey

@Serializable
data object DashboardRoute : AppRoute

@Serializable
data class ListDetailRoute(val listId: String) : AppRoute

@Serializable
data class CreateListRoute(val editingId: String? = null) : AppRoute

@Serializable
data class ItemDetailRoute(val listId: String, val itemId: String) : AppRoute

private val navigationSavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(DashboardRoute::class, DashboardRoute.serializer())
            subclass(ListDetailRoute::class, ListDetailRoute.serializer())
            subclass(CreateListRoute::class, CreateListRoute.serializer())
            subclass(ItemDetailRoute::class, ItemDetailRoute.serializer())
        }
    }
}

/**
 * The authenticated navigation graph.
 *
 * This is composed only from the `SessionGate`'s `Ready` branch, so every
 * ViewModel and repository listener reachable from here is created strictly after
 * session resolution. [accountEmail] and [onSignOut] come from the resolved session;
 * they are passed down rather than re-resolved so no screen below needs its own
 * `AuthRepository` handle.
 */
@Composable
fun AppNavHost(
    accountEmail: String?,
    onSignOut: () -> Unit,
) {
    val backStack = rememberNavBackStack(
        navigationSavedStateConfiguration,
        DashboardRoute,
    )

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<DashboardRoute> {
                DashboardScreen(
                    onOpenList = { backStack.add(ListDetailRoute(it)) },
                    onCreateList = { backStack.add(CreateListRoute()) },
                    accountEmail = accountEmail,
                    onSignOut = onSignOut,
                )
            }
            entry<ListDetailRoute> { route ->
                ListDetailScreen(
                    listId = route.listId,
                    onBack = { backStack.removeLastOrNull() },
                    onEditList = { backStack.add(CreateListRoute(it)) },
                    onOpenItem = { backStack.add(ItemDetailRoute(route.listId, it)) },
                )
            }
            entry<CreateListRoute> { route ->
                CreateListScreen(
                    editingId = route.editingId,
                    onDismiss = { backStack.removeLastOrNull() },
                    onCreated = { newListId ->
                        backStack.removeLastOrNull()
                        backStack.add(ListDetailRoute(newListId))
                    },
                )
            }
            entry<ItemDetailRoute> { route ->
                ItemDetailScreen(
                    listId = route.listId,
                    itemId = route.itemId,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}
