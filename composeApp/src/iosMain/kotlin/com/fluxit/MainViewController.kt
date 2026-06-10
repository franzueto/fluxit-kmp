package com.fluxit

import androidx.compose.ui.window.ComposeUIViewController
import com.fluxit.di.appModule
import com.fluxit.di.platformModule
import org.koin.core.context.startKoin
import platform.UIKit.UIViewController

private var koinStarted = false

fun MainViewController(): UIViewController {
    if (!koinStarted) {
        startKoin { modules(platformModule(), appModule) }
        koinStarted = true
    }
    return ComposeUIViewController { App() }
}
