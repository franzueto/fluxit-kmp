package com.fluxit.di

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fluxit.data.FluxItDatabase
import com.fluxit.data.IosPhotoPicker
import com.fluxit.data.IosPhotoStorage
import com.fluxit.data.PhotoPicker
import com.fluxit.data.PhotoStorage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
private fun documentsPath(): String {
    val url = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    return requireNotNull(url?.path) { "Unable to resolve documents directory" }
}

actual fun platformModule(): Module = module {
    single<FluxItDatabase> {
        Room.databaseBuilder<FluxItDatabase>(name = documentsPath() + "/fluxit.db")
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()
    }
    single<PhotoPicker> { IosPhotoPicker() }
    single<PhotoStorage> { IosPhotoStorage(documentsPath()) }
}
