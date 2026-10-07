package com.fluxit.firebase.storage

import com.fluxit.data.remote.RepositoryErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSError

/**
 * mapping-table tests for the Storage side of the boundary, discharging
 * the iOS counterpart of Android's `AndroidFirebaseStorageErrorMappingTest`,
 * mirroring `IosFirestoreErrorMappingTest`'s style for the Firestore side.
 *
 * Honest bound: this asserts the *mapping*, not that the Apple Storage SDK actually emits
 * `FIRStorageErrorDomain` with these exact codes for these situations - the same bound
 * `IosFirestoreErrorMappingTest` already documents for Firestore.
 */
class IosFirebaseStorageErrorMappingTest {

    private fun storageError(code: Long): PhotoStorageIosException =
        PhotoStorageIosException(NSError.errorWithDomain(domain = FIREBASE_STORAGE_ERROR_DOMAIN, code = code, userInfo = null))

    @Test
    fun everyDocumentedStorageCodeMapsToSomeBackendErrorCodeWithoutThrowing() {
        val codes = listOf(
            STORAGE_ERROR_OBJECT_NOT_FOUND,
            STORAGE_ERROR_UNAUTHORIZED,
            STORAGE_ERROR_QUOTA_EXCEEDED,
            STORAGE_ERROR_UNAUTHENTICATED,
            STORAGE_ERROR_RETRY_LIMIT_EXCEEDED,
        )
        for (code in codes) {
            storageBackendErrorCode(code)
        }
    }

    @Test
    fun permissionDenialMapsToForbidden() {
        // "permission" category.
        val mapped = storageError(STORAGE_ERROR_UNAUTHORIZED).toApplicationError()
        assertEquals(RepositoryErrorCode.FORBIDDEN, mapped.code)
        assertEquals(false, mapped.canRetry)
    }

    @Test
    fun unauthenticatedMapsToSessionRequired() {
        // "auth" category (a repository-operation-level auth failure, distinct from the
        // interactive sign-in/sign-up AuthError taxonomy).
        val mapped = storageError(STORAGE_ERROR_UNAUTHENTICATED).toApplicationError()
        assertEquals(RepositoryErrorCode.SESSION_REQUIRED, mapped.code)
        assertEquals(true, mapped.requiresFreshSession)
    }

    @Test
    fun retryLimitExceededMapsToOfflineAndIsRetryable() {
        // "unavailable/offline" category.
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
    }

    @Test
    fun anUnmappedStorageCodeFallsBackToUnknownRatherThanCrashing() {
        // "unknown" category.
        val mapped = storageError(-13040L).toApplicationError() // cancelled - no clean equivalent
        assertEquals(RepositoryErrorCode.UNKNOWN, mapped.code)
        assertEquals(true, mapped.canRetry)
    }

    @Test
    fun anErrorOutsideTheStorageDomainMapsToUnknownRatherThanLeaking() {
        val transportError = PhotoStorageIosException(
            NSError.errorWithDomain(domain = "NSURLErrorDomain", code = -1009L, userInfo = null),
        )

        val mapped = transportError.toApplicationError()

        assertEquals(RepositoryErrorCode.UNKNOWN, mapped.code)
    }
}
