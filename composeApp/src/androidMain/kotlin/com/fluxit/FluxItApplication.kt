package com.fluxit

import android.app.Application
import com.fluxit.di.appModule
import com.fluxit.di.platformModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class FluxItApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@FluxItApplication)
            modules(platformModule(), appModule)
        }
    }
}
