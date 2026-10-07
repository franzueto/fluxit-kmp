package com.fluxit.firebase.session

import com.fluxit.firebase.WebBridgeError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

internal class MemoryMarkerStore(var value: Boolean = false, var failWrites: Boolean = false) :
    WebSessionCleanup.MarkerStore {
    override fun read() = value
    override fun write(pending: Boolean): Boolean {
        if (failWrites) return false
        value = pending
        return true
    }
}

class WebSessionCleanupTest {

    @Test
    fun beginAndCompleteToggleTheDurableMarker() {
        val store = MemoryMarkerStore()
        val cleanup = WebSessionCleanup(markerStore = store, clearData = { it(null) })

        assertFalse(cleanup.pending)
        cleanup.begin()
        assertTrue(cleanup.pending)
        cleanup.complete()
        assertFalse(cleanup.pending)
    }

    @Test
    fun aMarkerLeftByAnInterruptedSessionIsPendingOnTheNextLoad() {
        val cleanup = WebSessionCleanup(markerStore = MemoryMarkerStore(value = true), clearData = { it(null) })

        assertTrue(cleanup.pending)
    }

    @Test
    fun anUnwritableMarkerFailsLoudlySoTheSessionStaysClosed() {
        val cleanup = WebSessionCleanup(markerStore = MemoryMarkerStore(failWrites = true), clearData = { it(null) })

        assertFailsWith<IllegalStateException> { cleanup.begin() }
        assertFailsWith<IllegalStateException> { cleanup.complete() }
    }

    @Test
    fun clearSucceedsWhenTheBridgeReportsNoError() = runTest {
        var calls = 0
        val cleanup = WebSessionCleanup(markerStore = MemoryMarkerStore(), clearData = { calls++; it(null) })

        cleanup.clear()

        assertEquals(1, calls)
    }

    @Test
    fun clearFailsWhenTheBridgeReportsAnError() = runTest {
        val cleanup = WebSessionCleanup(
            markerStore = MemoryMarkerStore(),
            clearData = { it(WebBridgeError("failed-precondition", "")) },
        )

        assertFailsWith<IllegalStateException> { cleanup.clear() }
    }
}
