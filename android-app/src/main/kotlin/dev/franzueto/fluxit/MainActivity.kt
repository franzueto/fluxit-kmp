package dev.franzueto.fluxit

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.franzueto.fluxit.platform.photo.ActivityResultRegistryProvider
import dev.franzueto.fluxit.shared.state.store.RootIntent
import dev.franzueto.fluxit.shared.state.store.RootStore
import dev.franzueto.fluxit.ui.FluxItRoot
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val registryProvider: ActivityResultRegistryProvider by inject()
    private val rootStore: RootStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Transparent bars + light icons (dark-only theme): pass a dark scrim so
        // the system keeps icons light on our #101822 chrome.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        dispatchDeepLink(intent)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchDeepLink(intent)
    }

    private fun dispatchDeepLink(intent: Intent?) {
        val url = intent?.data?.toString() ?: return
        rootStore.dispatch(RootIntent.OpenDeepLink(url))
    }
}
