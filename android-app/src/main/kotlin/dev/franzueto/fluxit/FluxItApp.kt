package dev.franzueto.fluxit

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class FluxItApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@FluxItApp)
            modules(emptyList())
        }
    }
}
