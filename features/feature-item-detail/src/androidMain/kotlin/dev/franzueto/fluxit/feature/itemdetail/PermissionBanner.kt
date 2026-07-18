@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.itemdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.franzueto.fluxit.core.designsystem.components.FluxItCard
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography

enum class PermissionTarget {
    Camera,
    Library,
}

private fun bannerCopy(target: PermissionTarget): String =
    when (target) {
        PermissionTarget.Camera -> "Camera access is off. Enable it in Settings to take photos."
        PermissionTarget.Library -> "Photo access is off. Enable it in Settings to choose a photo."
    }

@Composable
internal fun PermissionBanner(
    target: PermissionTarget,
    onOpenSettings: () -> Unit,
) {
    FluxItCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(FluxItSpacing.scaleMd),
            verticalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleSm),
        ) {
            Text(
                text = bannerCopy(target),
                style = FluxItTypography.bodyMd,
                color = FluxItColors.textPrimary,
            )
            TextButton(onClick = onOpenSettings) { Text("Open Settings") }
        }
    }
}
