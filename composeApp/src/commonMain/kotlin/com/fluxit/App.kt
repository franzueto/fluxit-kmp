package com.fluxit

import androidx.compose.runtime.Composable
import com.fluxit.feature.auth.SessionGate
import com.fluxit.ui.theme.FluxItTheme

/**
 * Application root.
 *
 * FB-104: the navigation graph is no longer composed directly here. [SessionGate] owns
 * that decision and composes it only once the session has resolved to an authenticated
 * user, so no user-scoped screen, ViewModel or listener can exist before then.
 */
@Composable
fun App() {
    FluxItTheme {
        SessionGate()
    }
}
