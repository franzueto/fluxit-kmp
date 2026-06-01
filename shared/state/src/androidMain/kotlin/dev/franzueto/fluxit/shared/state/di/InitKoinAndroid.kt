package dev.franzueto.fluxit.shared.state.di

import android.content.Context
import dev.franzueto.fluxit.shared.data.db.DriverFactory
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Android composition-root entry point (ADR-015 Slice C, Phase 06 Slice 7).
 * Mirrors [initKoinIos]: supplies the two platform-specific bindings the common
 * [initKoin] graph is missing and starts Koin.
 *
 * 1. The native `SqlDriver` — `DriverFactory(context).create()` over the app
 *    `Context`. The driver type is inferred (no `app.cash.sqldelight` import) so
 *    SQLDelight stays encapsulated in `:shared:data` (DataLayerArchTest).
 * 2. `androidContext(context)` — installed via the `appDeclaration` block so the
 *    real `remindersModule()` / `photoModule()` Android actuals (WorkManager,
 *    `ActivityResultRegistry` holder) can resolve `androidContext()`. The interim
 *    Slice-6 modules didn't need this; the real ones do.
 *
 * Pass the `Application` (not an `Activity`) as [context] — the graph outlives any
 * one Activity. Called once from `FluxItApp.onCreate()`.
 */
public fun initKoinAndroid(context: Context) {
    initKoin(
        extra = listOf(module { single { DriverFactory(context).create() } }),
        appDeclaration = { androidContext(context) },
    )
}
