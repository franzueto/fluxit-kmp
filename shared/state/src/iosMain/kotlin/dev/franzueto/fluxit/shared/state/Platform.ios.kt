package dev.franzueto.fluxit.shared.state

import platform.UIKit.UIDevice

actual class Platform actual constructor() {
    actual val name: String = "${UIDevice.currentDevice.systemName()} ${UIDevice.currentDevice.systemVersion}"
}
