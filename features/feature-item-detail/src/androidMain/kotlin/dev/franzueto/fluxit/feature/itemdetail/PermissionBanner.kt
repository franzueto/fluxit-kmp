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

/**
 * Which access the in-photo permission banner is asking for (§4). Drives the copy
 * only; the recovery action is the same "Open Settings" deep link for both.
 *
 * **Divergence (plan/10 §0 b):** there is no soft-vs-hard split — the domain
 * surfaces a flat `PermissionDenied` — and on Android the system camera owns its
 * own permission, so this banner is effectively iOS-camera-only. It is wired here
 * for parity; the `Request*` effects that raise it don't fire on Android today.
 */
enum class PermissionTarget {
    Camera,
    Library,
}

private fun bannerCopy(target: PermissionTarget): String =
    when (target) {
        PermissionTarget.Camera -> "Camera access is off. Enable it in Settings to take photos."
        PermissionTarget.Library -> "Photo access is off. Enable it in Settings to choose a photo."
    }

/** §4 in-section permission affordance: contextual card with an Open Settings CTA. */
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
