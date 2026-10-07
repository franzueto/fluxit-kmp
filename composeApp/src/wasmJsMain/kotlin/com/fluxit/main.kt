package com.fluxit

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.fluxit.di.appModule
import com.fluxit.di.platformModule
import kotlinx.browser.document
import org.koin.core.context.startKoin

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    startKoin { modules(platformModule(), appModule) }
    ComposeViewport(document.body!!) { App() }
}
