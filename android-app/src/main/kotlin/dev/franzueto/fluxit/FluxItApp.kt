package dev.franzueto.fluxit

import android.app.Application
import dev.franzueto.fluxit.shared.state.di.initKoinAndroid

class FluxItApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoinAndroid(this)
    }
}
