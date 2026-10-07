package com.fluxit.di

import com.fluxit.config.AppFeatures
import com.fluxit.data.PhotoPicker
import com.fluxit.data.PhotoStorage
import com.fluxit.data.WebPhotoPicker
import com.fluxit.data.WebPhotoStorage
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListRepository
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.session.SessionAuthRepository
import com.fluxit.domain.session.SessionCleanup
import com.fluxit.domain.session.SessionItemRepository
import com.fluxit.domain.session.SessionListRepository
import com.fluxit.domain.session.SessionPhotoStorage
import com.fluxit.domain.session.SessionWork
import com.fluxit.firebase.auth.WebAuthRepository
import com.fluxit.firebase.item.WebFirebaseItemRepository
import com.fluxit.firebase.list.WebFirebaseListRepository
import com.fluxit.firebase.session.WebSessionCleanup
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Web bindings: Firebase Auth, Firestore and Storage (docs/web-app/PROGRESS.md, Phases 2–4).
 * Firebase starts on first use, not here.
 */
actual fun platformModule(): Module = module {
    single { AppFeatures.Web }
    single { SessionWork() }
    single<SessionCleanup> { WebSessionCleanup() }

    // Same wrapping as Android and iOS: the session starts Unresolved and opens only
    // through restoreSession() or an interactive sign-in.
    single<AuthRepository> { SessionAuthRepository(WebAuthRepository(), get(), get()) }

    // Firestore-backed, gated by the session like Android and iOS. Constructors stay lazy
    // with respect to uid paths and listeners.
    single<ListRepository> { SessionListRepository(WebFirebaseListRepository(), get()) }
    single<ItemRepository> { SessionItemRepository(WebFirebaseItemRepository(), get()) }

    single<PhotoPicker> { WebPhotoPicker() }
    // Cloud Storage-backed and session-gated, like iOS; see WebPhotoStorage's KDoc.
    single<PhotoStorage> { SessionPhotoStorage(WebPhotoStorage(), get()) }
}
