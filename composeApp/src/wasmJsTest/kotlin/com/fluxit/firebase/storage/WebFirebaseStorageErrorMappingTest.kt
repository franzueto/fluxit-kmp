package com.fluxit.firebase.storage

import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.firebase.WebBridgeError
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Storage mapping table, ported from `IosFirebaseStorageErrorMappingTest`. Same bound:
 * this asserts the mapping, not that the JS SDK emits these codes in these situations.
 */
class WebFirebaseStorageErrorMappingTest {

    private fun storageError(code: String) = PhotoStorageWebException(WebBridgeError(code, "message"))

    @Test
    fun permissionDenialMapsToForbidden() {
        val mapped = storageError(STORAGE_ERROR_UNAUTHORIZED).toApplicationError()
        assertEquals(RepositoryErrorCode.FORBIDDEN, mapped.code)
        assertEquals(false, mapped.canRetry)
    }

    @Test
    fun unauthenticatedMapsToSessionRequired() {
        val mapped = storageError(STORAGE_ERROR_UNAUTHENTICATED).toApplicationError()
        assertEquals(RepositoryErrorCode.SESSION_REQUIRED, mapped.code)
        assertEquals(true, mapped.requiresFreshSession)
    }

    @Test
    fun retryLimitExceededMapsToOfflineAndIsRetryable() {
        val mapped = storageError(STORAGE_ERROR_RETRY_LIMIT_EXCEEDED).toApplicationError()
        assertEquals(RepositoryErrorCode.OFFLINE, mapped.code)
        assertEquals(true, mapped.canRetry)
    }

    @Test
    fun quotaExceededMapsToQuotaAndIsNotRetryable() {
        val mapped = storageError(STORAGE_ERROR_QUOTA_EXCEEDED).toApplicationError()
        assertEquals(RepositoryErrorCode.QUOTA, mapped.code)
        assertEquals(false, mapped.canRetry)
    }

    @Test
    fun objectNotFoundMapsToNotFound() {
        val mapped = storageError(STORAGE_ERROR_OBJECT_NOT_FOUND).toApplicationError()
        assertEquals(RepositoryErrorCode.NOT_FOUND, mapped.code)
        assertEquals(true, WebBridgeError(STORAGE_ERROR_OBJECT_NOT_FOUND, "").isStorageObjectNotFound())
        assertEquals(false, WebBridgeError(STORAGE_ERROR_UNAUTHORIZED, "").isStorageObjectNotFound())
    }

    @Test
    fun unmappedStorageCodesFallBackToUnknownRatherThanCrashing() {
        for (code in listOf("storage/canceled", "storage/bucket-not-found", "storage/download-size-exceeded", "unknown")) {
            val mapped = storageError(code).toApplicationError()
            assertEquals(RepositoryErrorCode.UNKNOWN, mapped.code, code)
            assertEquals(true, mapped.canRetry, code)
        }
    }

    @Test
    fun aFirestoreSpelledCodeIsNotMistakenForAStorageCode() {
        // Firestore's "permission-denied" has no "storage/" prefix; only the Storage spelling maps.
        assertEquals(RepositoryErrorCode.UNKNOWN, storageError("permission-denied").toApplicationError().code)
    }
}
