package com.fluxit.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fluxit.data.AndroidPhotoPicker
import com.fluxit.data.AndroidPhotoStorage
import com.fluxit.data.FluxItDatabase
import com.fluxit.data.PhotoPicker
import com.fluxit.data.PhotoStorage
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.firebase.auth.AndroidAuthRepository
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single<FluxItDatabase> {
        val context: Context = androidContext()
        Room.databaseBuilder<FluxItDatabase>(
            context = context,
            name = context.getDatabasePath("fluxit.db").absolutePath,
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }
    single<PhotoPicker> { AndroidPhotoPicker() }
    single<PhotoStorage> { AndroidPhotoStorage(androidContext()) }

    // FB-102: the Android Firebase Auth adapter. Bound here rather than in the shared
    // module because the implementation is platform-specific; the iOS binding is
    // FB-103's. Nothing in `appModule` injects AuthRepository yet (FB-104 adds the
    // first consumer), so iOS Koin resolution is unaffected by this binding existing
    // only on Android.
    single<AuthRepository> { AndroidAuthRepository() }
}
