package dev.franzueto.fluxit.core.designsystem.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors

// Phase 02 §5: applies background.dark + safe-area handling + optional sticky
// top/bottom slots. Header/tab-bar blur is layered on by FluxItTopBar /
// FluxItBottomTabBar themselves (see §7); the Scaffold is just the chrome.

@Composable
@Suppress("ktlint:standard:function-naming")
public fun FluxItScaffold(
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = topBar,
        bottomBar = bottomBar,
        containerColor = FluxItColors.backgroundDark,
        contentColor = FluxItColors.textPrimary,
        content = content,
    )
}
