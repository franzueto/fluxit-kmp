package dev.franzueto.fluxit.platform.photo

import dev.franzueto.fluxit.shared.domain.port.PhotoCapture
import dev.franzueto.fluxit.shared.domain.port.PhotoStorage
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

public actual fun photoModule(): Module =
    module {
        single { ActivityResultRegistryProvider() }
        single<PhotoEncoder> { AndroidPhotoEncoder() }
        single<PhotoStorage> { AndroidPhotoStorage(androidContext()) }
        single<PhotoCapture> { AndroidPhotoCapture(androidContext(), get()) }
    }
