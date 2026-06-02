@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.franzueto.fluxit.core.designsystem.components.FluxItBottomTabBar
import dev.franzueto.fluxit.core.designsystem.components.FluxItEmptyState
import dev.franzueto.fluxit.core.designsystem.components.FluxItFab
import dev.franzueto.fluxit.core.designsystem.components.FluxItScaffold
import dev.franzueto.fluxit.core.designsystem.components.FluxItTabItem
import dev.franzueto.fluxit.core.designsystem.icons.Account
import dev.franzueto.fluxit.core.designsystem.icons.AccountFilled
import dev.franzueto.fluxit.core.designsystem.icons.Calendar
import dev.franzueto.fluxit.core.designsystem.icons.CalendarFilled
import dev.franzueto.fluxit.core.designsystem.icons.FluxItIcons
import dev.franzueto.fluxit.core.designsystem.icons.List
import dev.franzueto.fluxit.core.designsystem.icons.ListFilled
import dev.franzueto.fluxit.core.designsystem.icons.Plus
import dev.franzueto.fluxit.core.designsystem.icons.Star
import dev.franzueto.fluxit.core.designsystem.icons.StarFilled
import dev.franzueto.fluxit.core.designsystem.theme.FluxItTheme
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.feature.lists.DashboardRoute
import dev.franzueto.fluxit.shared.state.navigation.Tab
import dev.franzueto.fluxit.shared.state.store.InitState
import dev.franzueto.fluxit.shared.state.store.RootEffect
import dev.franzueto.fluxit.shared.state.store.RootIntent
import dev.franzueto.fluxit.shared.state.store.RootStore
import org.koin.compose.koinInject

/**
 * The Compose composition root (Phase 06 Slice 7; nav graph fleshed out in Phase
 * 07 Slice 4). Resolves the session-scoped [RootStore] from Koin, runs
 * `InitializeApp` once via [RootIntent.AppStarted], and gates the NavHost on the
 * resulting [InitState]:
 *
 * - [InitState.Initializing] → a splash spinner.
 * - [InitState.Failed] → a minimal retry surface (the polished splash/error UX is
 *   a later phase; this only needs the graph reachable).
 * - [InitState.Ready] → the [FluxItNavHost], whose start destination is the tab
 *   host (Lists Dashboard tab).
 */
@Composable
fun FluxItRoot() {
    FluxItTheme {
        val rootStore = koinInject<RootStore>()
        val state by rootStore.state.collectAsState()

        LaunchedEffect(Unit) { rootStore.dispatch(RootIntent.AppStarted) }

        when (val init = state.init) {
            InitState.Initializing -> Splash()
            is InitState.Failed ->
                StartupError(message = init.message, onRetry = { rootStore.dispatch(RootIntent.AppStarted) })
            InitState.Ready -> FluxItNavHost(rootStore)
        }
    }
}

@Composable
private fun Splash() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun StartupError(
    message: String,
    onRetry: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message)
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = FluxItSpacing.scaleSm)) {
                Text("Retry")
            }
        }
    }
}

/**
 * The app NavHost (plan/07 §1, §6). The start destination [ROUTE_DASHBOARD] is the
 * [TabHost] (tab bar + center FAB driven off [RootStore.currentTab]); the other
 * routes are pushed destinations. Screens not yet built (list/item detail,
 * create-list, settings) render [Placeholder] until their feature phases land
 * (08 / 09 / Slice 6).
 *
 * App-level deep links (reminder taps; plan/06 §5) arrive as
 * [RootEffect.NavigateToList] / [RootEffect.NavigateToItem] one-shots off the
 * [RootStore]; the [LaunchedEffect] below translates them into `navController`
 * pushes. Per-screen navigation (dashboard row taps, FAB → create) is wired in the
 * dashboard slice.
 */
@Composable
private fun FluxItNavHost(rootStore: RootStore) {
    val navController = rememberNavController()

    LaunchedEffect(navController) {
        rootStore.effects.collect { effect ->
            when (effect) {
                is RootEffect.NavigateToList -> navController.navigate("list/${effect.id.value}")
                // A bare item deep link can't build the nested list/{id}/item/{id}
                // route without a parent-list lookup (a Phase 08 use case); land on
                // a standalone item placeholder for now.
                is RootEffect.NavigateToItem -> navController.navigate("item/${effect.id.value}")
                RootEffect.NavigateToOnboarding -> Unit // v2 placeholder, no consumer in v1.
                is RootEffect.ShowFatalError -> Unit // surfaced by the init gate in FluxItRoot.
            }
        }
    }

    NavHost(navController = navController, startDestination = ROUTE_DASHBOARD) {
        composable(ROUTE_DASHBOARD) { TabHost(rootStore, navController) }
        composable(
            route = ROUTE_LIST_DETAIL,
            arguments = listOf(navArgument(ARG_LIST_ID) { type = NavType.StringType }),
        ) { Placeholder("List detail") }
        composable(
            route = ROUTE_ITEM_DETAIL,
            arguments =
                listOf(
                    navArgument(ARG_LIST_ID) { type = NavType.StringType },
                    navArgument(ARG_ITEM_ID) { type = NavType.StringType },
                ),
        ) { Placeholder("Item detail") }
        composable(
            route = ROUTE_ITEM_DEEP_LINK,
            arguments = listOf(navArgument(ARG_ITEM_ID) { type = NavType.StringType }),
        ) { Placeholder("Item detail") }
        composable(ROUTE_CREATE_LIST) { Placeholder("Create list") }
        composable(ROUTE_SETTINGS) { Placeholder("Settings") }
    }
}

/**
 * The bottom-tab host (plan/07 §2). The four tabs render unconditionally to
 * preserve the design; the selected tab is owned by [RootStore.currentTab] and a
 * tap dispatches [RootIntent.TabSelected]. The body swaps on the current tab —
 * Lists is the live [DashboardRoute]; Calendar / Starred / Account show inline
 * placeholders until their slices land (config-gated "Coming soon" routing is a
 * Slice 6 concern). The center-docked FAB overlays the bottom bar.
 */
@Composable
private fun TabHost(
    rootStore: RootStore,
    navController: NavHostController,
) {
    val state by rootStore.state.collectAsState()
    val currentTab = state.currentTab

    Box {
        FluxItScaffold(
            bottomBar = {
                FluxItBottomTabBar(
                    tabs = TAB_ITEMS,
                    selectedIndex = currentTab.ordinal,
                    onSelect = { index -> rootStore.dispatch(RootIntent.TabSelected(Tab.entries[index])) },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (currentTab) {
                    Tab.Lists ->
                        DashboardRoute(
                            onOpenList = { id -> navController.navigate("list/${id.value}") },
                            onCreateList = { navController.navigate(ROUTE_CREATE_LIST) },
                            onComingSoon = { tab -> rootStore.dispatch(RootIntent.TabSelected(tab)) },
                            onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                        )
                    Tab.Calendar -> ComingSoon("Calendar")
                    Tab.Starred -> ComingSoon("Starred")
                    Tab.Account -> Placeholder("Account")
                }
            }
        }
        FluxItFab(
            icon = FluxItIcons.Plus,
            onClick = { navController.navigate(ROUTE_CREATE_LIST) },
            contentDescription = "Create new list",
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = FluxItSpacing.scaleXl),
        )
    }
}

@Composable
private fun ComingSoon(feature: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        FluxItEmptyState(
            title = "$feature is coming soon",
            message = "Coming in a future update.",
        )
    }
}

@Composable
private fun Placeholder(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(label)
    }
}

private val TAB_ITEMS =
    listOf(
        FluxItTabItem(icon = FluxItIcons.List, activeIcon = FluxItIcons.ListFilled, label = "Lists"),
        FluxItTabItem(icon = FluxItIcons.Calendar, activeIcon = FluxItIcons.CalendarFilled, label = "Calendar"),
        FluxItTabItem(icon = FluxItIcons.Star, activeIcon = FluxItIcons.StarFilled, label = "Starred"),
        FluxItTabItem(icon = FluxItIcons.Account, activeIcon = FluxItIcons.AccountFilled, label = "Account"),
    )

private const val ARG_LIST_ID = "listId"
private const val ARG_ITEM_ID = "itemId"
private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_LIST_DETAIL = "list/{$ARG_LIST_ID}"
private const val ROUTE_ITEM_DETAIL = "list/{$ARG_LIST_ID}/item/{$ARG_ITEM_ID}"
private const val ROUTE_ITEM_DEEP_LINK = "item/{$ARG_ITEM_ID}"
private const val ROUTE_CREATE_LIST = "create-list"
private const val ROUTE_SETTINGS = "settings"
