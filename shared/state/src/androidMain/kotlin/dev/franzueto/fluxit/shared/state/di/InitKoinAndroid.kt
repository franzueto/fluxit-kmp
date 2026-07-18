package dev.franzueto.fluxit.shared.state.di

import android.content.Context
import dev.franzueto.fluxit.shared.data.db.DriverFactory
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Pass the `Application` (not an `Activity`) as [context] — the graph outlives any
 * one Activity. Called once from `FluxItApp.onCreate()`.
 */
public fun initKoinAndroid(context: Context) {
    initKoin(
        extra = listOf(module { single { DriverFactory(context).create() } }),
        appDeclaration = { androidContext(context) },
    )
}
