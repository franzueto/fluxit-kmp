package com.fluxit.firebase.storage

import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest

@JsModule("./firebase-bridge-test-support.mjs")
private external object StorageTaskSupport : JsAny {
    fun describeStorageCancellation(viaClearSessionData: Boolean, done: (String) -> Unit)
}

/**
 * Session cleanup's Storage share in `firebase-bridge.mjs`, with fake operations: running
 * uploads are cancelled and awaited, downloads (which the JS SDK cannot cancel) are answered
 * with `storage/canceled` at once and their late result dropped, finished tasks are left
 * alone, and every caller hears back exactly once.
 */
class WebStorageSessionTasksTest {

    private suspend fun describe(viaClearSessionData: Boolean): String = suspendCancellableCoroutine { continuation ->
        StorageTaskSupport.describeStorageCancellation(viaClearSessionData) { continuation.resume(it) }
    }

    @Test
    fun cancellingStopsRunningTasksAndAnswersEveryCallerOnce() = runTest {
        assertEquals(
            """{"seen":["throws:storage/invalid-argument","finished:ok","download:storage/canceled",""" +
                """"upload:storage/canceled","cleanup:ok"],"uploadCancelled":1}""",
            describe(viaClearSessionData = false),
        )
    }

    @Test
    fun clearSessionDataCancelsStorageTasksBeforeReportingSuccess() = runTest {
        assertEquals(
            """{"seen":["throws:storage/invalid-argument","finished:ok","download:storage/canceled",""" +
                """"upload:storage/canceled","cleanup:ok"],"uploadCancelled":1}""",
            describe(viaClearSessionData = true),
        )
    }
}
