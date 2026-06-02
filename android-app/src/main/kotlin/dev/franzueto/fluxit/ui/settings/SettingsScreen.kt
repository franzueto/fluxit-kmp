@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.franzueto.fluxit.core.designsystem.components.FluxItDashboardListItem
import dev.franzueto.fluxit.core.designsystem.components.FluxItSectionHeader
import dev.franzueto.fluxit.core.designsystem.components.FluxItTopBarCentered
import dev.franzueto.fluxit.core.designsystem.icons.ChevronRight
import dev.franzueto.fluxit.core.designsystem.icons.FluxItIcons
import dev.franzueto.fluxit.core.designsystem.icons.More
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography
import dev.franzueto.fluxit.ui.account.AccountViewModel
import dev.franzueto.fluxit.ui.debug.DebugActionsSection
import org.koin.compose.getKoin
import org.koin.core.parameter.parametersOf

private const val PRIVACY_POLICY_URL = "https://fluxit.example/privacy"
private const val TERMS_URL = "https://fluxit.example/terms"

/**
 * The Settings stub (plan/07 §12). A real destination behind the dashboard /
 * account gear, deliberately minimal — full Diagnostics + crash-reporting wiring
 * is Phase 16. Sections:
 *
 * - **About**: app version (from [AccountStore], interim literal until Phase 16
 *   routes it through config).
 * - **Privacy**: an "Anonymous crash reports" toggle. v1 is a no-op local
 *   toggle (the `platform-config`/Crashlytics binding lands in Phase 16); the
 *   "Takes effect on next launch" note matches the §12 copy. Privacy Policy / ToS
 *   rows open placeholder URLs in the system browser via [onOpenUrl].
 * - **Debug actions** (debug builds only): the [DebugActionsSection] seed button,
 *   stripped from release via source-set selection.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val koin = getKoin()
    val viewModel = viewModel { AccountViewModel { scope -> koin.get { parametersOf(scope) } } }
    val state by viewModel.store.state.collectAsState()

    var crashReports by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize()) {
        FluxItTopBarCentered(title = "Settings", backLabel = "Account", onBack = onBack)
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = FluxItSpacing.containerPadding),
            verticalArrangement = Arrangement.spacedBy(FluxItSpacing.stackGap),
        ) {
            FluxItSectionHeader(label = "About")
            FluxItDashboardListItem(
                icon = FluxItIcons.More,
                iconTint = FluxItColors.textMuted,
                title = "Version",
                subtitle = state.version,
            )

            FluxItSectionHeader(label = "Privacy")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = FluxItSpacing.itemPaddingX),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Anonymous crash reports", style = FluxItTypography.bodyMd, color = FluxItColors.textPrimary)
                    Text("Takes effect on next launch", style = FluxItTypography.captionXs, color = FluxItColors.textMuted)
                }
                Switch(checked = crashReports, onCheckedChange = { crashReports = it })
            }
            FluxItDashboardListItem(
                icon = FluxItIcons.More,
                iconTint = FluxItColors.textMuted,
                title = "Privacy Policy",
                onClick = { onOpenUrl(PRIVACY_POLICY_URL) },
                chevronIcon = FluxItIcons.ChevronRight,
            )
            FluxItDashboardListItem(
                icon = FluxItIcons.More,
                iconTint = FluxItColors.textMuted,
                title = "Terms of Service",
                onClick = { onOpenUrl(TERMS_URL) },
                chevronIcon = FluxItIcons.ChevronRight,
            )

            DebugActionsSection(modifier = Modifier.fillMaxWidth())
        }
    }
}
