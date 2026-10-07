package com.fluxit.di

import com.fluxit.config.AppFeatures
import com.fluxit.data.IosPhotoPicker
import com.fluxit.data.IosPhotoStorage
import com.fluxit.data.PhotoPicker
import com.fluxit.data.PhotoStorage
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListRepository
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.firebase.auth.IosAuthRepository
import com.fluxit.firebase.item.IosFirebaseItemRepository
import com.fluxit.firebase.list.IosFirebaseListRepository
import com.fluxit.domain.session.*
import com.fluxit.firebase.session.IosSessionCleanup
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single { AppFeatures.Mobile }
    single { SessionWork() }
    single<SessionCleanup> { IosSessionCleanup() }
    single<PhotoPicker> { IosPhotoPicker() }
    // Real Cloud Storage-backed; see IosPhotoStorage's KDoc.
    single<PhotoStorage> { SessionPhotoStorage(IosPhotoStorage(), get()) }

    // The iOS Firebase Auth adapter, mirroring the Android binding. The
    // implementation is platform-specific (the Swift-only Firebase boundary on iOS: it delegates to Swift), so it is
    // bound here rather than in `appModule`. Nothing in `appModule` injects
    // AuthRepository yet - adds the first consumer - so this binding being a
    // `single` means the Swift bridge is not looked up until then, well after
    // `FirebaseBootstrap.start()` has registered it.
    single<AuthRepository> { SessionAuthRepository(IosAuthRepository(), get(), get()) }

    // Ordinary application bindings always use Firebase. Constructors remain
    // lazy with respect to UID paths/listeners.
    single<ListRepository> { SessionListRepository(IosFirebaseListRepository(), get()) }
    single<ItemRepository> { SessionItemRepository(IosFirebaseItemRepository(), get()) }
}
