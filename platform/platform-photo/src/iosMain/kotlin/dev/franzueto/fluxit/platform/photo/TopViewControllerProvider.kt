package dev.franzueto.fluxit.platform.photo

import platform.UIKit.UIViewController

/**
 * Supplies the view controller that [IosPhotoCapture] presents the camera / picker
 * from (plan/06 §7 host-holder pattern). The default impl walks from the key
 * window's root controller down the `presentedViewController` chain so it presents
 * from whatever is frontmost; an app can override to present from a known host.
 */
public fun interface TopViewControllerProvider {
    public fun topViewController(): UIViewController?
}
