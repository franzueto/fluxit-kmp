package com.fluxit.firebase.list

import com.fluxit.data.remote.BackendErrorCode
import com.fluxit.data.remote.RepositoryErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSError

/**
 * mapping table tests for the Firestore side of the boundary - the iOS
 * counterpart of Android's `FirestoreErrorMappingTest`.
 *
 * These raw `FIRFirestoreErrorCode` values are gRPC status codes, the same ones
 * `FirebaseFirestoreException.Code` reports on Android, so this table is deliberately
 * identical to `FirestoreErrorMapping.kt`'s `toBackendErrorCode()` - a divergence here
 * would mean the two platforms report different neutral errors for the same backend
 * failure. Honest bound: this asserts the *mapping*, not that the Apple Firestore SDK
 * actually emits `FIRFirestoreErrorDomain` with these codes for these situations.
 */
class IosFirestoreErrorMappingTest {

    private fun firestoreError(code: Long): NSError =
        NSError.errorWithDomain(domain = FIREBASE_FIRESTORE_ERROR_DOMAIN, code = code, userInfo = null)

    @Test
    fun everyGrpcStatusCodeMapsToSomeBackendErrorCodeWithoutThrowing() {
        for (code in 0L..16L) {
            firestoreBackendErrorCode(code)
        }
    }

    @Test
    fun explicitlyDocumentedCodesMapExactly() {
        assertEquals(BackendErrorCode.UNAUTHENTICATED, firestoreBackendErrorCode(16L))
        assertEquals(BackendErrorCode.PERMISSION_DENIED, firestoreBackendErrorCode(7L))
        assertEquals(BackendErrorCode.UNAVAILABLE, firestoreBackendErrorCode(14L))
        assertEquals(BackendErrorCode.DEADLINE_EXCEEDED, firestoreBackendErrorCode(4L))
        assertEquals(BackendErrorCode.NOT_FOUND, firestoreBackendErrorCode(5L))
        assertEquals(BackendErrorCode.ALREADY_EXISTS, firestoreBackendErrorCode(6L))
        assertEquals(BackendErrorCode.INVALID_ARGUMENT, firestoreBackendErrorCode(3L))
        assertEquals(BackendErrorCode.RESOURCE_EXHAUSTED, firestoreBackendErrorCode(8L))
    }

    @Test
    fun everyOtherCodeFallsBackToUnknownRatherThanCrashing() {
        assertEquals(BackendErrorCode.UNKNOWN, firestoreBackendErrorCode(1L)) // cancelled
        assertEquals(BackendErrorCode.UNKNOWN, firestoreBackendErrorCode(13L)) // internal
        assertEquals(BackendErrorCode.UNKNOWN, firestoreBackendErrorCode(0L)) // ok
    }

    @Test
    fun aFirestoreDomainErrorBecomesARepositoryExceptionCarryingTheMappedApplicationError() {
        val mapped = firestoreError(7L).toRepositoryException() // permissionDenied

        assertEquals(RepositoryErrorCode.FORBIDDEN, mapped.error.code)
    }

    @Test
    fun anErrorOutsideTheFirestoreDomainMapsToUnknownRatherThanLeaking() {
        val transportError = NSError.errorWithDomain(domain = "NSURLErrorDomain", code = -1009L, userInfo = null)

        val mapped = transportError.toRepositoryException()

        assertEquals(RepositoryErrorCode.UNKNOWN, mapped.error.code)
    }
}
