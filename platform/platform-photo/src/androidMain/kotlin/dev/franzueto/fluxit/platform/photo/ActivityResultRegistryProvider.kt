package dev.franzueto.fluxit.platform.photo

import androidx.activity.result.ActivityResultRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Host-holder for the current Activity's [ActivityResultRegistry] (plan/06 §7).
 *
 * `PhotoCapture` lives in androidMain but needs an Activity to launch the camera /
 * picker — and the domain port can't carry one. The Activity pushes its registry
 * here on resume and clears it on pause; [AndroidPhotoCapture] waits for a non-null
 * registry (short timeout → `CaptureError.Unknown`) before launching. A single
 * app-scoped holder is bound in `photoModule()`; future host-needing capabilities
 * (file picker, share sheet) should follow the same pattern.
 */
public class ActivityResultRegistryProvider {
    private val registryFlow = MutableStateFlow<ActivityResultRegistry?>(null)

    /** Latest registry, or null while no Activity is resumed. */
    public val current: StateFlow<ActivityResultRegistry?> = registryFlow

    /** Called by the Activity on resume. */
    public fun attach(registry: ActivityResultRegistry) {
        registryFlow.value = registry
    }

    /** Called by the Activity on pause, only clearing if it still owns the slot. */
    public fun detach(registry: ActivityResultRegistry) {
        registryFlow.compareAndSet(registry, null)
    }
}
