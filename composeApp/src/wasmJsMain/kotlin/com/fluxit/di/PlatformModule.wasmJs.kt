package com.fluxit.di

import com.fluxit.config.AppFeatures
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListRepository
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.session.SessionAuthRepository
import com.fluxit.domain.session.SessionCleanup
import com.fluxit.domain.session.SessionWork
import com.fluxit.firebase.auth.WebAuthRepository
import com.fluxit.firebase.session.WebSessionCleanup
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Web bindings. Auth is Firebase-backed from Phase 2; the list, item and photo bindings
 * arrive in Phases 3–4 (docs/web-app/PROGRESS.md). Firebase starts on first use, not here.
 */
actual fun platformModule(): Module = module {
    single { AppFeatures.Web }
    single { SessionWork() }
    single<SessionCleanup> { WebSessionCleanup() }

    // Same wrapping as Android and iOS: the session starts Unresolved and opens only
    // through restoreSession() or an interactive sign-in.
    single<AuthRepository> { SessionAuthRepository(WebAuthRepository(), get(), get()) }

    // Phase 2 placeholders until the Firestore bridge lands in Phase 3.
    single<ListRepository> { PendingWebListRepository() }
    single<ItemRepository> { PendingWebItemRepository() }
}
