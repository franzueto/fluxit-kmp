package com.fluxit.firebase.list

import com.fluxit.data.remote.ApplicationError
import com.fluxit.data.remote.BackendErrorCode
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.toApplicationError
import com.fluxit.data.remote.toRepositoryError
import com.google.firebase.firestore.FirebaseFirestoreException

/**
 * Thrown by [AndroidFirebaseListRepository] - from a `suspend` function or by closing a
 * `callbackFlow` - instead of ever letting a [FirebaseFirestoreException] or any other
 * Firebase SDK exception type escape into `commonMain`-visible code. Callers only ever
 * see FB-201's neutral [ApplicationError].
 */
internal class ListRepositoryException(val error: ApplicationError) : Exception()

/**
 * Maps a [FirebaseFirestoreException.Code] onto FB-201's neutral [BackendErrorCode].
 *
 * Every gRPC-style status the Firestore SDK can report is covered explicitly except the
 * ones with no meaningful neutral equivalent (`OK`, `CANCELLED`, `ABORTED`,
 * `OUT_OF_RANGE`, `UNIMPLEMENTED`, `INTERNAL`, `DATA_LOSS`, `FAILED_PRECONDITION`),
 * which fall through to [BackendErrorCode.UNKNOWN] - a safe, retryable-by-caller-policy
 * default rather than a crash.
 */
internal fun FirebaseFirestoreException.Code.toBackendErrorCode(): BackendErrorCode = when (this) {
    FirebaseFirestoreException.Code.UNAUTHENTICATED -> BackendErrorCode.UNAUTHENTICATED
    FirebaseFirestoreException.Code.PERMISSION_DENIED -> BackendErrorCode.PERMISSION_DENIED
    FirebaseFirestoreException.Code.UNAVAILABLE -> BackendErrorCode.UNAVAILABLE
    FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> BackendErrorCode.DEADLINE_EXCEEDED
    FirebaseFirestoreException.Code.NOT_FOUND -> BackendErrorCode.NOT_FOUND
    FirebaseFirestoreException.Code.ALREADY_EXISTS -> BackendErrorCode.ALREADY_EXISTS
    FirebaseFirestoreException.Code.INVALID_ARGUMENT -> BackendErrorCode.INVALID_ARGUMENT
    FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> BackendErrorCode.RESOURCE_EXHAUSTED
    else -> BackendErrorCode.UNKNOWN
}

internal fun FirebaseFirestoreException.toApplicationError(): ApplicationError =
    code.toBackendErrorCode().toRepositoryError().toApplicationError()

/** Converts any failure from a Firestore SDK call into a [ListRepositoryException]. */
internal fun Throwable.toListRepositoryException(): ListRepositoryException = when (this) {
    is FirebaseFirestoreException -> ListRepositoryException(toApplicationError())
    else -> ListRepositoryException(RepositoryErrorCode.UNKNOWN.toApplicationError())
}
