package com.fluxit.firebase.list

import com.fluxit.data.remote.BackendErrorCode
import com.fluxit.data.remote.RepositoryErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Firestore JS error codes → neutral errors; the same table as iOS
 * (`IosFirestoreErrorMappingTest`) and Android, spelled as the JS SDK's codes.
 */
class WebFirestoreErrorMappingTest {

    @Test
    fun explicitlyDocumentedCodesMapExactly() {
        assertEquals(BackendErrorCode.UNAUTHENTICATED, firestoreBackendErrorCode("unauthenticated"))
        assertEquals(BackendErrorCode.PERMISSION_DENIED, firestoreBackendErrorCode("permission-denied"))
        assertEquals(BackendErrorCode.UNAVAILABLE, firestoreBackendErrorCode("unavailable"))
        assertEquals(BackendErrorCode.DEADLINE_EXCEEDED, firestoreBackendErrorCode("deadline-exceeded"))
        assertEquals(BackendErrorCode.NOT_FOUND, firestoreBackendErrorCode("not-found"))
        assertEquals(BackendErrorCode.ALREADY_EXISTS, firestoreBackendErrorCode("already-exists"))
        assertEquals(BackendErrorCode.INVALID_ARGUMENT, firestoreBackendErrorCode("invalid-argument"))
        assertEquals(BackendErrorCode.RESOURCE_EXHAUSTED, firestoreBackendErrorCode("resource-exhausted"))
    }

    @Test
    fun everyOtherCodeFallsBackToUnknownRatherThanCrashing() {
        for (code in listOf("cancelled", "aborted", "internal", "failed-precondition", "out-of-range", "data-loss", "unknown", "")) {
            assertEquals(BackendErrorCode.UNKNOWN, firestoreBackendErrorCode(code), code)
        }
    }

    @Test
    fun aFirestoreErrorBecomesARepositoryExceptionCarryingTheMappedApplicationError() {
        assertEquals(RepositoryErrorCode.FORBIDDEN, firestoreError("permission-denied").toRepositoryException().error.code)
        assertEquals(RepositoryErrorCode.SESSION_REQUIRED, firestoreError("unauthenticated").toRepositoryException().error.code)
        assertEquals(RepositoryErrorCode.OFFLINE, firestoreError("unavailable").toRepositoryException().error.code)
    }

    @Test
    fun aNonFirestoreFailureMapsToUnknownRatherThanLeaking() {
        // A JS exception without a Firestore code reaches Kotlin as the bridge's "unknown".
        assertEquals(RepositoryErrorCode.UNKNOWN, firestoreError("unknown").toRepositoryException().error.code)
    }
}
