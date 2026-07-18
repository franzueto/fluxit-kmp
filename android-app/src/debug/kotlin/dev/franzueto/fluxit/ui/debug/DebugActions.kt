@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.franzueto.fluxit.core.designsystem.components.FluxItPrimaryButton
import dev.franzueto.fluxit.core.designsystem.components.FluxItSectionHeader
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.state.debug.SeedSampleData
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun DebugActionsSection(modifier: Modifier) {
    val seed = koinInject<SeedSampleData>()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FluxItSpacing.stackGap),
    ) {
        FluxItSectionHeader(label = "Debug")
        FluxItPrimaryButton(
            label = if (running) "Seeding…" else "Seed sample data",
            enabled = !running,
            onClick = {
                running = true
                status = null
                scope.launch {
                    status =
                        when (val result = seed()) {
                            is Outcome.Ok -> "Seeded ${result.value} sample lists."
                            is Outcome.Err -> "Seed failed: ${result.error}"
                        }
                    running = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        status?.let {
            Text(text = it, style = FluxItTypography.labelSm, color = FluxItColors.textMuted)
        }
    }
}
