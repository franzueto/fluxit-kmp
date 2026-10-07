package com.fluxit.di

import com.fluxit.config.AppFeatures
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.session.SessionAuthRepository
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
}
