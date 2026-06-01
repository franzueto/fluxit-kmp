package dev.franzueto.fluxit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.franzueto.fluxit.platform.photo.ActivityResultRegistryProvider
import dev.franzueto.fluxit.ui.FluxItRoot
import org.koin.android.ext.android.inject

/**
 * The single Activity host (Phase 06 Slice 7). Two jobs:
 *
 * 1. **Host-holder wiring (plan/06 §7).** `AndroidPhotoCapture` launches the
 *    camera / picker through this Activity's [ActivityResultRegistry], which the
 *    domain port can't carry. We push it into the app-scoped
 *    [ActivityResultRegistryProvider] on resume and clear it on pause via a
 *    lifecycle observer — without this, photo capture waits for a registry and
 *    times out.
 * 2. **Compose host.** [FluxItRoot] resolves `RootStore` from Koin and drives the
 *    splash → Lists Dashboard NavHost.
 */
class MainActivity : ComponentActivity() {
    private val registryProvider: ActivityResultRegistryProvider by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Attach/detach the registry around the resumed window so capture only
        // ever launches against a live Activity (the holder uses compareAndSet on
        // detach, so a fast resume of a new Activity can't be cleared by the old).
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    registryProvider.attach(activityResultRegistry)
                }

                override fun onPause(owner: LifecycleOwner) {
                    registryProvider.detach(activityResultRegistry)
                }
            },
        )

        setContent { FluxItRoot() }
    }
}
