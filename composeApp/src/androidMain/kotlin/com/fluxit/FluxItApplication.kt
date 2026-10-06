package com.fluxit

import android.app.Application
import com.fluxit.di.appModule
import com.fluxit.di.platformModule
import com.fluxit.firebase.AndroidFirebaseInitializer
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class FluxItApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidFirebaseInitializer.initialize(this)
        startKoin {
            androidContext(this@FluxItApplication)
            modules(platformModule(), appModule)
        }
    }
}
