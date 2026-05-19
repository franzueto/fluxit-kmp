package dev.franzueto.fluxit.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography

public data class FluxItTabItem(
    val icon: ImageVector,
    val activeIcon: ImageVector = icon,
    val label: String,
)

@Composable
@Suppress("ktlint:standard:function-naming")
public fun FluxItBottomTabBar(
    tabs: List<FluxItTabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    // §7-resolved opaque fallback for the bar background until the blur
    // perf path lands.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(FluxItColors.surfaceCard.copy(alpha = 0.9f))
                .navigationBarsPadding()
                .height(80.dp)
                .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            TabItem(
                tab = tab,
                active = index == selectedIndex,
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun TabItem(
    tab: FluxItTabItem,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (active) FluxItColors.primaryBlue else FluxItColors.textMuted
    Column(
        modifier =
            modifier
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (active) tab.activeIcon else tab.icon,
            contentDescription = tab.label,
            tint = tint,
        )
        Text(
            text = tab.label,
            style = FluxItTypography.captionXs,
            color = tint,
        )
    }
}
