package com.fluxit.di

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
    single { SessionWork() }
    single<SessionCleanup> { IosSessionCleanup() }
    single<PhotoPicker> { IosPhotoPicker() }
    // FB-305: real Cloud Storage-backed; see IosPhotoStorage's KDoc.
    single<PhotoStorage> { SessionPhotoStorage(IosPhotoStorage(), get()) }

    // FB-103: the iOS Firebase Auth adapter, mirroring FB-102's Android binding. The
    // implementation is platform-specific (PLAN-008: it delegates to Swift), so it is
    // bound here rather than in `appModule`. Nothing in `appModule` injects
    // AuthRepository yet - FB-104 adds the first consumer - so this binding being a
    // `single` means the Swift bridge is not looked up until then, well after
    // `FirebaseBootstrap.start()` has registered it.
    single<AuthRepository> { SessionAuthRepository(IosAuthRepository(), get(), get()) }

    // FB-702: ordinary application bindings always use Firebase. Constructors remain
    // lazy with respect to UID paths/listeners.
    single<ListRepository> { SessionListRepository(IosFirebaseListRepository(), get()) }
    single<ItemRepository> { SessionItemRepository(IosFirebaseItemRepository(), get()) }
}
