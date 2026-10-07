package com.fluxit.di

import com.fluxit.config.AppFeatures
import kotlin.test.Test
import kotlin.test.assertEquals
import org.koin.dsl.koinApplication

class WebPlatformModuleTest {

    @Test
    fun webBindsTheSignInOnlyFeatureSet() {
        val koin = koinApplication { modules(platformModule()) }.koin

        assertEquals(AppFeatures.Web, koin.get<AppFeatures>())
    }
}
