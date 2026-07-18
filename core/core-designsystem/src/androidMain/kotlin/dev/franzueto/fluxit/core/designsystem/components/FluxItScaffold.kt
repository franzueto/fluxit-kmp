package dev.franzueto.fluxit.core.designsystem.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors

// top/bottom slots. Header/tab-bar blur is layered on by FluxItTopBar /

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
