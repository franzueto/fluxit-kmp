package com.fluxit.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fluxit.config.FirebaseDevFlags
import com.fluxit.data.AndroidPhotoPicker
import com.fluxit.data.AndroidPhotoStorage
import com.fluxit.data.FluxItDatabase
import com.fluxit.data.PhotoPicker
import com.fluxit.data.PhotoStorage
import com.fluxit.data.RoomItemRepository
import com.fluxit.data.RoomListRepository
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListRepository
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.firebase.auth.AndroidAuthRepository
import com.fluxit.firebase.item.AndroidFirebaseItemRepository
import com.fluxit.firebase.list.AndroidFirebaseListRepository
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

    // FB-207: moved out of the common `appModule` because only this platform module has
    // both implementations available. Behind `FirebaseDevFlags.USE_FIREBASE_REPOSITORIES`
    // (default false, so ordinary builds are unaffected): off binds Room exactly as
    // before, on binds the FB-202/FB-204 Firebase adapters. `AndroidFirebaseListRepository`/
    // `AndroidFirebaseItemRepository`'s default constructors only grab `FirebaseFirestore`
    // handles here - they resolve `currentUid`/register listeners lazily per call (see
    // their KDoc), and this `single { }` lambda itself does not run until something
    // actually injects `ListRepository`/`ItemRepository`, which for every app ViewModel
    // means the FB-104/FB-105 authenticated session scope. No pre-auth listener or
    // uid-path resolution results from this binding existing.
    single<ListRepository> {
        selectRepositoryBinding(
            useFirebaseRepositories = FirebaseDevFlags.USE_FIREBASE_REPOSITORIES,
            firebase = { AndroidFirebaseListRepository() },
            room = { RoomListRepository(get()) },
        )
    }
    single<ItemRepository> {
        selectRepositoryBinding(
            useFirebaseRepositories = FirebaseDevFlags.USE_FIREBASE_REPOSITORIES,
            firebase = { AndroidFirebaseItemRepository() },
            room = { RoomItemRepository(get()) },
        )
    }
}
