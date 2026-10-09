package com.fluxit.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FirebaseWebOptionsTest {
    private val configured = FirebaseWebOptions(
        apiKey = "configured-key",
        authDomain = "configured.example.test",
        projectId = "configured-project",
        storageBucket = "configured-bucket",
        messagingSenderId = "1",
        appId = "configured-app",
    )

    @Test
    fun emulatorBuildsUseTheDemoProjectEvenWithAWebConfig() {
        assertSame(FirebaseWebOptions.Emulator, FirebaseWebOptions.forBuild(emulatorEnabled = true, configured = configured))
        assertSame(FirebaseWebOptions.Emulator, FirebaseWebOptions.forBuild(emulatorEnabled = true, configured = null))
    }

    @Test
    fun otherBuildsUseTheWebConfig() {
        assertSame(configured, FirebaseWebOptions.forBuild(emulatorEnabled = false, configured = configured))
        assertNull(FirebaseWebOptions.forBuild(emulatorEnabled = false, configured = null))
    }

    @Test
    fun theEmulatorOptionsNameOnlyTheDemoProject() {
        val emulator = FirebaseWebOptions.Emulator
        assertEquals("demo-fluxit", emulator.projectId)
        assertTrue(emulator.authDomain.startsWith("demo-fluxit."))
        assertTrue(emulator.storageBucket.startsWith("demo-fluxit."))
        assertTrue(emulator.apiKey.startsWith("demo-"))
    }
}
