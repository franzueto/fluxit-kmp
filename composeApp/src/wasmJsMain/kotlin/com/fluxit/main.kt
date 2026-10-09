package com.fluxit

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.fluxit.di.appModule
import com.fluxit.di.platformModule
import com.fluxit.navigation.BrowserBackNavigation
import kotlinx.browser.document
import org.koin.core.context.startKoin

/**
 * Renders into `#app`, which `index.html`/`web-shell.js` keep inside the safe areas and above
 * the on-screen keyboard. The loading splash stays up until the first composition.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    startKoin { modules(platformModule(), appModule) }
    ComposeViewport(document.getElementById("app")!!) {
        BrowserBackNavigation()
        App()
        LaunchedEffect(Unit) { document.getElementById("splash")?.remove() }
    }
}
