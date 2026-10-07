package com.fluxit.firebase.storage

import com.fluxit.data.remote.BackendErrorCode
import com.google.firebase.storage.StorageException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * mapping-table tests for the Storage side of the boundary, discharging
 * on Android - the counterpart of `FirestoreErrorMappingTest` and the iOS
 * `IosFirebaseStorageErrorMappingTest`.
 *
 * Scope bound, deliberate (same one `FirebaseAuthErrorMapperTest` documents): this JVM suite
 * exercises [Int.toStorageBackendErrorCode] directly against [StorageException]'s documented
 * `public static final int` code constants, rather than constructing a [StorageException]
 * instance - its factory methods (`fromException`/`fromErrorStatus`) call `android.util.Log`,
 * which is unmocked under this module's plain JVM unit tests (no Robolectric configured), the
 * same reason `FirestoreErrorMappingTest` lives in `androidInstrumentedTest` instead. The
 * mapping this file actually cares about - SDK error code -> neutral [BackendErrorCode] - is
 * a pure `Int -> BackendErrorCode` function and needs no SDK instance to exercise fully; the
 * one-line `StorageException.toApplicationError()` wrapper is untested delegation on top of it.
 */
class AndroidFirebaseStorageErrorMappingTest {

    @Test
    fun everyDocumentedStorageCodeMapsToSomeBackendErrorCodeWithoutThrowing() {
        val codes = listOf(
            StorageException.ERROR_UNKNOWN,
            StorageException.ERROR_OBJECT_NOT_FOUND,
            StorageException.ERROR_BUCKET_NOT_FOUND,
            StorageException.ERROR_PROJECT_NOT_FOUND,
            StorageException.ERROR_QUOTA_EXCEEDED,
            StorageException.ERROR_NOT_AUTHENTICATED,
            StorageException.ERROR_NOT_AUTHORIZED,
            StorageException.ERROR_RETRY_LIMIT_EXCEEDED,
            StorageException.ERROR_INVALID_CHECKSUM,
            StorageException.ERROR_CANCELED,
        )
        for (code in codes) {
            code.toStorageBackendErrorCode()
        }
    }

    @Test
    fun explicitlyDocumentedCodesMapExactly() {
        // "permission" category.
        assertEquals(BackendErrorCode.PERMISSION_DENIED, StorageException.ERROR_NOT_AUTHORIZED.toStorageBackendErrorCode())
        // "auth" category (repository-operation-level, not interactive sign-in).
        assertEquals(BackendErrorCode.UNAUTHENTICATED, StorageException.ERROR_NOT_AUTHENTICATED.toStorageBackendErrorCode())
        // "unavailable/offline" category.
        assertEquals(BackendErrorCode.UNAVAILABLE, StorageException.ERROR_RETRY_LIMIT_EXCEEDED.toStorageBackendErrorCode())
        assertEquals(BackendErrorCode.NOT_FOUND, StorageException.ERROR_OBJECT_NOT_FOUND.toStorageBackendErrorCode())
        assertEquals(BackendErrorCode.RESOURCE_EXHAUSTED, StorageException.ERROR_QUOTA_EXCEEDED.toStorageBackendErrorCode())
    }

    @Test
    fun everyOtherCodeFallsBackToUnknownRatherThanCrashing() {
        // "unknown" category.
        assertEquals(BackendErrorCode.UNKNOWN, StorageException.ERROR_UNKNOWN.toStorageBackendErrorCode())
        assertEquals(BackendErrorCode.UNKNOWN, StorageException.ERROR_BUCKET_NOT_FOUND.toStorageBackendErrorCode())
        assertEquals(BackendErrorCode.UNKNOWN, StorageException.ERROR_PROJECT_NOT_FOUND.toStorageBackendErrorCode())
        assertEquals(BackendErrorCode.UNKNOWN, StorageException.ERROR_INVALID_CHECKSUM.toStorageBackendErrorCode())
        assertEquals(BackendErrorCode.UNKNOWN, StorageException.ERROR_CANCELED.toStorageBackendErrorCode())
    }

    @Test
    fun mirrorsAndroidsAndIosStorageErrorRawValuesAreTheSameSdkConstants() {
        // Documents the cross-platform correspondence `IosFirebaseStorageErrorMapping.kt`
        // hardcodes as Long literals (that file cannot import FirebaseStorage).
        assertEquals(-13010, StorageException.ERROR_OBJECT_NOT_FOUND)
        assertEquals(-13021, StorageException.ERROR_NOT_AUTHORIZED)
    }
}
