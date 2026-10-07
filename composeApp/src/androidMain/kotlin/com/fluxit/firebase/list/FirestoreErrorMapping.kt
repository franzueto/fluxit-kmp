package com.fluxit.firebase.list

import com.fluxit.data.remote.ApplicationError
import com.fluxit.data.remote.BackendErrorCode
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.RepositoryException
import com.fluxit.data.remote.toApplicationError
import com.fluxit.data.remote.toRepositoryError
import com.google.firebase.firestore.FirebaseFirestoreException

/**
 * Maps a [FirebaseFirestoreException.Code] onto the neutral [BackendErrorCode].
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

/** Converts any failure from a Firestore SDK call into a [RepositoryException]. */
internal fun Throwable.toRepositoryException(): RepositoryException = when (this) {
    is FirebaseFirestoreException -> RepositoryException(toApplicationError())
    else -> RepositoryException(RepositoryErrorCode.UNKNOWN.toApplicationError())
}
