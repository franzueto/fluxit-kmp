package com.fluxit.di

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fluxit.data.FluxItDatabase
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
    // FB-305: real Cloud Storage-backed, no longer needs a local `documentsPath()` base
    // directory - see IosPhotoStorage's KDoc.
    single<PhotoStorage> { IosPhotoStorage() }

    // FB-103: the iOS Firebase Auth adapter, mirroring FB-102's Android binding. The
    // implementation is platform-specific (PLAN-008: it delegates to Swift), so it is
    // bound here rather than in `appModule`. Nothing in `appModule` injects
    // AuthRepository yet - FB-104 adds the first consumer - so this binding being a
    // `single` means the Swift bridge is not looked up until then, well after
    // `FirebaseBootstrap.start()` has registered it.
    single<AuthRepository> { IosAuthRepository() }

    // FB-702: ordinary application bindings always use Firebase. Constructors remain
    // lazy with respect to UID paths/listeners. The unused Room database definition
    // is retained for FB-703 removal; neither repository resolves it.
    single<ListRepository> { IosFirebaseListRepository() }
    single<ItemRepository> { IosFirebaseItemRepository() }
}
