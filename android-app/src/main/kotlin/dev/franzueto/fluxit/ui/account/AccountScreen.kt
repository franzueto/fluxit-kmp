@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.franzueto.fluxit.core.designsystem.components.FluxItDashboardListItem
import dev.franzueto.fluxit.core.designsystem.components.FluxItTopBarLarge
import dev.franzueto.fluxit.core.designsystem.icons.ChevronRight
import dev.franzueto.fluxit.core.designsystem.icons.FluxItIcons
import dev.franzueto.fluxit.core.designsystem.icons.Settings
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.shared.state.store.AccountEffect
import dev.franzueto.fluxit.shared.state.store.AccountIntent
import dev.franzueto.fluxit.ui.debug.DebugActionsSection
import org.koin.compose.getKoin
import org.koin.core.parameter.parametersOf

/**
 * The Account tab (plan/07 §2/§7/§12). Resolves the session [AccountStore] scoped
 * to an [AccountViewModel], shows the app version, a row into the Settings stub,
 * and — in debug builds only — the [DebugActionsSection] ("Seed sample data").
 *
 * It renders inside the shell's tab-host scaffold (which owns the bottom bar +
 * FAB), so like the dashboard it draws its own inline `FluxItTopBarLarge` header
 * rather than nesting a scaffold.
 *
 * The store's two intents both map to navigation effects; only [OpenSettings] has
 * a real destination today (`About` lives inside the Settings stub), so both
 * effects route there. The `when` is exhaustive so a new effect breaks the build.
 */
@Composable
fun AccountScreen(onOpenSettings: () -> Unit) {
    val koin = getKoin()
    val viewModel = viewModel { AccountViewModel { scope -> koin.get { parametersOf(scope) } } }
    val store = viewModel.store
    val state by store.state.collectAsState()

    LaunchedEffect(store) {
        store.effects.collect { effect ->
            when (effect) {
                AccountEffect.NavigateToSettings -> onOpenSettings()
                AccountEffect.NavigateToAbout -> onOpenSettings()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(FluxItSpacing.stackGap),
    ) {
        FluxItTopBarLarge(title = "Account")
        FluxItDashboardListItem(
            icon = FluxItIcons.Settings,
            iconTint = FluxItColors.primaryBlue,
            title = "Settings",
            subtitle = "Version ${state.version}",
            onClick = { store.dispatch(AccountIntent.OpenSettings) },
            chevronIcon = FluxItIcons.ChevronRight,
            modifier = Modifier.padding(horizontal = FluxItSpacing.containerPadding),
        )
        DebugActionsSection(
            modifier = Modifier.padding(horizontal = FluxItSpacing.containerPadding),
        )
    }
}
