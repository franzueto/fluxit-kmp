package com.fluxit

import androidx.compose.runtime.Composable
import com.fluxit.navigation.AppNavHost
import com.fluxit.ui.theme.FluxItTheme

@Composable
fun App() {
    FluxItTheme {
        AppNavHost()
    }
}
