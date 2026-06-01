@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.franzueto.fluxit.core.designsystem.theme.FluxItTheme
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.feature.lists.DashboardRoute
import dev.franzueto.fluxit.shared.state.store.InitState
import dev.franzueto.fluxit.shared.state.store.RootIntent
import dev.franzueto.fluxit.shared.state.store.RootStore
import org.koin.compose.koinInject

/**
 * The Compose composition root (Phase 06 Slice 7). Resolves the session-scoped
 * [RootStore] from Koin, runs `InitializeApp` once via [RootIntent.AppStarted],
 * and gates the NavHost on the resulting [InitState]:
 *
 * - [InitState.Initializing] → a splash spinner.
 * - [InitState.Failed] → a minimal retry surface (the polished splash/error UX is
 *   a later phase; Slice 7 only needs the graph reachable).
 * - [InitState.Ready] → the [FluxItNavHost], whose start destination is the Lists
 *   Dashboard.
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
            InitState.Ready -> FluxItNavHost()
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
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message)
            androidx.compose.material3.TextButton(onClick = onRetry, modifier = Modifier.padding(top = FluxItSpacing.scaleSm)) {
                Text("Retry")
            }
        }
    }
}

/**
 * The app NavHost. Slice 7 ships the single Lists Dashboard destination; list
 * detail / create-list routes land with their feature phases. Kept distinct from
 * [FluxItRoot] so the splash/init gate and the navigation graph stay separable.
 */
@Composable
private fun FluxItNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = LISTS_ROUTE) {
        composable(LISTS_ROUTE) { DashboardRoute() }
    }
}

private const val LISTS_ROUTE = "lists"
