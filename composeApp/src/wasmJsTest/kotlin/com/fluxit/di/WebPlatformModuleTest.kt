package com.fluxit.di

import com.fluxit.config.AppFeatures
import com.fluxit.data.PhotoPicker
import com.fluxit.data.PhotoStorage
import com.fluxit.data.WebPhotoPicker
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListRepository
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.session.SessionAuthRepository
import com.fluxit.domain.session.SessionItemRepository
import com.fluxit.domain.session.SessionListRepository
import com.fluxit.domain.session.SessionPhotoStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.koin.dsl.koinApplication

class WebPlatformModuleTest {

    @Test
    fun webBindsTheSignInOnlyFeatureSet() {
        val koin = koinApplication { modules(platformModule()) }.koin

        assertEquals(AppFeatures.Web, koin.get<AppFeatures>())
    }

    @Test
    fun webAuthIsWrappedInTheSessionRepositoryWithoutStartingFirebase() {
        val koin = koinApplication { modules(platformModule()) }.koin

        // Resolving the graph must not need the Firebase web config or touch the SDK.
        assertIs<SessionAuthRepository>(koin.get<AuthRepository>())
    }

    @Test
    fun webListsAndItemsAreFirestoreBackedAndSessionGatedWithoutStartingFirebase() {
        val koin = koinApplication { modules(platformModule()) }.koin

        assertIs<SessionListRepository>(koin.get<ListRepository>())
        assertIs<SessionItemRepository>(koin.get<ItemRepository>())
    }

    @Test
    fun webPhotosUseTheFilePickerAndSessionGatedStorageWithoutStartingFirebase() {
        val koin = koinApplication { modules(platformModule()) }.koin

        assertIs<WebPhotoPicker>(koin.get<PhotoPicker>())
        assertIs<SessionPhotoStorage>(koin.get<PhotoStorage>())
    }
}
