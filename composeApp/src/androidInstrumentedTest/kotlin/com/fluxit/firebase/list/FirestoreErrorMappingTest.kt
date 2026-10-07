package com.fluxit.firebase.list

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fluxit.data.remote.BackendErrorCode
import com.fluxit.data.remote.RepositoryErrorCode
import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.IOException
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * tests for the Firestore SDK error mapping.
 *
 * Instrumented rather than a JVM unit test: [FirebaseFirestoreException.Code]'s static
 * initializer touches `android.util.SparseArray`
 * (`FirebaseFirestoreException$Code.buildStatusList`), which plain JVM unit tests in
 * this module cannot satisfy (no Robolectric is configured) - confirmed by first
 * writing this as an `androidUnitTest` and observing
 * `RuntimeException: Method get in android.util.SparseArray not mocked`. A real Android
 * runtime resolves it, so this lives here instead.
 *
 * Guards two things: every [FirebaseFirestoreException.Code] the SDK can report
 * resolves to *some* neutral [BackendErrorCode] (never crashes on an unmapped case),
 * and the codes this repository documents behaviour for map exactly where the
 * naming implies they should.
 */
@RunWith(AndroidJUnit4::class)
class FirestoreErrorMappingTest {

    @Test
    fun everySdkCodeMapsToSomeBackendErrorCodeWithoutThrowing() {
        for (code in FirebaseFirestoreException.Code.entries) {
            code.toBackendErrorCode()
        }
    }

    @Test
    fun explicitlyDocumentedCodesMapExactly() {
        assertEquals(BackendErrorCode.UNAUTHENTICATED, FirebaseFirestoreException.Code.UNAUTHENTICATED.toBackendErrorCode())
        assertEquals(BackendErrorCode.PERMISSION_DENIED, FirebaseFirestoreException.Code.PERMISSION_DENIED.toBackendErrorCode())
        assertEquals(BackendErrorCode.UNAVAILABLE, FirebaseFirestoreException.Code.UNAVAILABLE.toBackendErrorCode())
        assertEquals(BackendErrorCode.DEADLINE_EXCEEDED, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED.toBackendErrorCode())
        assertEquals(BackendErrorCode.NOT_FOUND, FirebaseFirestoreException.Code.NOT_FOUND.toBackendErrorCode())
        assertEquals(BackendErrorCode.ALREADY_EXISTS, FirebaseFirestoreException.Code.ALREADY_EXISTS.toBackendErrorCode())
        assertEquals(BackendErrorCode.INVALID_ARGUMENT, FirebaseFirestoreException.Code.INVALID_ARGUMENT.toBackendErrorCode())
        assertEquals(BackendErrorCode.RESOURCE_EXHAUSTED, FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED.toBackendErrorCode())
    }

    @Test
    fun everyOtherCodeFallsBackToUnknownRatherThanCrashing() {
        assertEquals(BackendErrorCode.UNKNOWN, FirebaseFirestoreException.Code.CANCELLED.toBackendErrorCode())
        assertEquals(BackendErrorCode.UNKNOWN, FirebaseFirestoreException.Code.INTERNAL.toBackendErrorCode())
        assertEquals(BackendErrorCode.UNKNOWN, FirebaseFirestoreException.Code.OK.toBackendErrorCode())
    }

    @Test
    fun aFirestoreExceptionBecomesARepositoryExceptionCarryingTheMappedApplicationError() {
        val sdkException = FirebaseFirestoreException("nope", FirebaseFirestoreException.Code.PERMISSION_DENIED)

        val mapped = (sdkException as Throwable).toRepositoryException()

        assertEquals(RepositoryErrorCode.FORBIDDEN, mapped.error.code)
    }

    @Test
    fun aNonFirestoreThrowableMapsToUnknownRatherThanLeaking() {
        val mapped = IOException("network down").toRepositoryException()

        assertEquals(RepositoryErrorCode.UNKNOWN, mapped.error.code)
    }
}
