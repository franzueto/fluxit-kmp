@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.ui.debug

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Release-build implementation of the debug-actions section: a no-op (plan/07
 * §7/§12). The debug-only seed action lives in `src/debug`'s twin of this file;
 * selecting this source set for release builds strips the `SeedSampleData`
 * reference from the shipped APK. [modifier] is accepted for call-site parity and
 * intentionally unused.
 */
@Composable
fun DebugActionsSection(
    @Suppress("UNUSED_PARAMETER") modifier: Modifier,
) {
    // No debug actions in release builds.
}
