package dev.franzueto.fluxit

import android.app.Application
import dev.franzueto.fluxit.shared.state.di.initKoinAndroid

/**
 * The Android composition root (Phase 06 Slice 7). Starts the single FluxIt Koin
 * graph at process start via [initKoinAndroid], which installs `androidContext`
 * (required by the real reminders/photo platform actuals) and the native
 * `SqlDriver`. The `Application` instance — not an Activity — backs the graph so
 * it outlives any one screen.
 */
class FluxItApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoinAndroid(this)
    }
}
