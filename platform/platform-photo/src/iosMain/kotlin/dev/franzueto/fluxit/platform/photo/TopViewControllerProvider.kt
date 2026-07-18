package dev.franzueto.fluxit.platform.photo

import platform.UIKit.UIViewController

public fun interface TopViewControllerProvider {
    public fun topViewController(): UIViewController?
}
