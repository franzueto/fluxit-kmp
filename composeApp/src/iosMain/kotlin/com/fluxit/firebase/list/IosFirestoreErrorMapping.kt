package com.fluxit.firebase.list

import com.fluxit.data.remote.ApplicationError
import com.fluxit.data.remote.BackendErrorCode
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.toApplicationError
import com.fluxit.data.remote.toRepositoryError
import platform.Foundation.NSError

/**
 * Thrown by [IosFirebaseListRepository] - from a `suspend` function or by closing a
 * `callbackFlow` - instead of ever letting a raw Firestore [NSError] escape into
 * `commonMain`-visible code. Callers only ever see FB-201's neutral [ApplicationError].
 * Exact counterpart of Android's `ListRepositoryException` in `FirestoreErrorMapping.kt`.
 */
internal class ListRepositoryException(val error: ApplicationError) : Exception()

/**
 * `FIRFirestoreErrorDomain`, spelled out so no Firebase symbol is referenced from
 * Kotlin (per PLAN-008, this file cannot import `FirebaseFirestore`).
 */
internal const val FIREBASE_FIRESTORE_ERROR_DOMAIN: String = "FIRFirestoreErrorDomain"

/**
 * Maps a `FIRFirestoreErrorCode` raw value (an `NSError.code` under
 * [FIREBASE_FIRESTORE_ERROR_DOMAIN]) onto FB-201's neutral [BackendErrorCode].
 *
 * These raw values are the same gRPC status codes the Android adapter's
 * `FirebaseFirestoreException.Code.toBackendErrorCode()` (`FirestoreErrorMapping.kt`)
 * maps on the other platform - both official SDKs surface the same underlying Firestore
 * backend status, just through a different platform exception type. Written as integer
 * literals rather than referenced through an SDK enum because this file must not import
 * `FirebaseFirestore`. Every code without a meaningful neutral equivalent (`OK`,
 * `CANCELLED`, `ABORTED`, `OUT_OF_RANGE`, `UNIMPLEMENTED`, `INTERNAL`, `DATA_LOSS`,
 * `FAILED_PRECONDITION`) falls through to [BackendErrorCode.UNKNOWN], matching Android.
 */
internal fun firestoreBackendErrorCode(code: Long): BackendErrorCode = when (code) {
    16L -> BackendErrorCode.UNAUTHENTICATED // unauthenticated
    7L -> BackendErrorCode.PERMISSION_DENIED // permissionDenied
    14L -> BackendErrorCode.UNAVAILABLE // unavailable
    4L -> BackendErrorCode.DEADLINE_EXCEEDED // deadlineExceeded
    5L -> BackendErrorCode.NOT_FOUND // notFound
    6L -> BackendErrorCode.ALREADY_EXISTS // alreadyExists
    3L -> BackendErrorCode.INVALID_ARGUMENT // invalidArgument
    8L -> BackendErrorCode.RESOURCE_EXHAUSTED // resourceExhausted
    else -> BackendErrorCode.UNKNOWN
}

/**
 * Maps [this] to FB-201's neutral [ApplicationError]. An [NSError] outside
 * [FIREBASE_FIRESTORE_ERROR_DOMAIN] (for example a transport-level `NSURLErrorDomain`
 * failure surfaced before Firestore's own gRPC layer ever answered) collapses to
 * [RepositoryErrorCode.UNKNOWN] - a safe, retryable-by-caller-policy default rather than
 * a crash, matching Android's `else -> BackendErrorCode.UNKNOWN` fallback.
 */
internal fun NSError.toApplicationError(): ApplicationError =
    if (domain == FIREBASE_FIRESTORE_ERROR_DOMAIN) {
        firestoreBackendErrorCode(code).toRepositoryError().toApplicationError()
    } else {
        RepositoryErrorCode.UNKNOWN.toApplicationError()
    }

/** Converts any Firestore-originated [NSError] into a [ListRepositoryException]. */
internal fun NSError.toListRepositoryException(): ListRepositoryException =
    ListRepositoryException(toApplicationError())
