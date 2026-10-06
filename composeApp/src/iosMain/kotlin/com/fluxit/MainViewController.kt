package com.fluxit

import androidx.compose.ui.window.ComposeUIViewController
import com.fluxit.di.appModule
import com.fluxit.di.platformModule
import org.koin.core.context.startKoin
import platform.UIKit.UIViewController

private var koinStarted = false

/** The application startup graph; separated from mounting UI for opt-in runtime checks. */
fun initializeIosKoin() {
    if (!koinStarted) {
        startKoin { modules(platformModule(), appModule) }
        koinStarted = true
    }
}

fun MainViewController(): UIViewController {
    initializeIosKoin()
    return ComposeUIViewController { App() }
}
