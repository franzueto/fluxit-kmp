package com.fluxit.di

import com.fluxit.data.AndroidPhotoPicker
import com.fluxit.data.AndroidPhotoStorage
import com.fluxit.data.PhotoPicker
import com.fluxit.data.PhotoStorage
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListRepository
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.firebase.auth.AndroidAuthRepository
import com.fluxit.firebase.item.AndroidFirebaseItemRepository
import com.fluxit.firebase.list.AndroidFirebaseListRepository
import com.fluxit.domain.session.*
import com.fluxit.firebase.session.AndroidSessionCleanup
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single { SessionWork() }
    single<SessionCleanup> { AndroidSessionCleanup(androidContext()) }
    single<PhotoPicker> { AndroidPhotoPicker() }
    // FB-304: AndroidPhotoStorage is now real Cloud Storage-backed and needs no
    // Android Context (it only resolves a FirebaseStorage handle and the signed-in
    // uid).
    single<PhotoStorage> { SessionPhotoStorage(AndroidPhotoStorage(), get()) }

    // FB-102: the Android Firebase Auth adapter. Bound here rather than in the shared
    // module because the implementation is platform-specific; the iOS binding is
    // FB-103's. Nothing in `appModule` injects AuthRepository yet (FB-104 adds the
    // first consumer), so iOS Koin resolution is unaffected by this binding existing
    // only on Android.
    single<AuthRepository> { SessionAuthRepository(AndroidAuthRepository(), get(), get()) }

    // FB-702: ordinary application bindings always use Firebase. Constructors remain
    // lazy with respect to UID paths/listeners.
    single<ListRepository> { SessionListRepository(AndroidFirebaseListRepository(), get()) }
    single<ItemRepository> { SessionItemRepository(AndroidFirebaseItemRepository(), get()) }
}
