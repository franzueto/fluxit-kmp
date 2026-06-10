package com.fluxit.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.savedstate.read
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fluxit.feature.createlist.CreateListScreen
import com.fluxit.feature.dashboard.DashboardScreen
import com.fluxit.feature.itemdetail.ItemDetailScreen
import com.fluxit.feature.listdetail.ListDetailScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val LIST_DETAIL = "list/{listId}"
    const val CREATE_LIST = "createList?editingId={editingId}"
    const val ITEM_DETAIL = "item/{itemId}"

    fun listDetail(listId: String) = "list/$listId"
    fun createList(editingId: String? = null) =
        if (editingId == null) "createList" else "createList?editingId=$editingId"
    fun itemDetail(itemId: String) = "item/$itemId"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onOpenList = { navController.navigate(Routes.listDetail(it)) },
                onCreateList = { navController.navigate(Routes.createList()) },
            )
        }
        composable(
            Routes.LIST_DETAIL,
            arguments = listOf(navArgument("listId") { type = NavType.StringType }),
        ) { entry ->
            val listId = entry.arguments?.read { getStringOrNull("listId") } ?: return@composable
            ListDetailScreen(
                listId = listId,
                onBack = { navController.popBackStack() },
                onEditList = { navController.navigate(Routes.createList(it)) },
                onOpenItem = { navController.navigate(Routes.itemDetail(it)) },
            )
        }
        composable(
            Routes.CREATE_LIST,
            arguments = listOf(
                navArgument("editingId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
        ) { entry ->
            val editingId = entry.arguments?.read { getStringOrNull("editingId") }
            CreateListScreen(
                editingId = editingId,
                onDismiss = { navController.popBackStack() },
                onCreated = { newListId ->
                    navController.popBackStack()
                    navController.navigate(Routes.listDetail(newListId))
                },
            )
        }
        composable(
            Routes.ITEM_DETAIL,
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) { entry ->
            val itemId = entry.arguments?.read { getStringOrNull("itemId") } ?: return@composable
            ItemDetailScreen(
                itemId = itemId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
