package com.fluxit.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fluxit.data.AndroidPhotoPicker
import com.fluxit.data.AndroidPhotoStorage
import com.fluxit.data.FluxItDatabase
import com.fluxit.data.PhotoPicker
import com.fluxit.data.PhotoStorage
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
}
