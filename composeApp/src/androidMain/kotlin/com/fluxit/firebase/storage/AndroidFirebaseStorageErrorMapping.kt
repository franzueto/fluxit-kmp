package com.fluxit.firebase.storage

import com.fluxit.data.remote.ApplicationError
import com.fluxit.data.remote.BackendErrorCode
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.toApplicationError
import com.fluxit.data.remote.toRepositoryError
import com.google.firebase.storage.StorageException

/**
 * `FB-401` discharges `FB-305-NB2` on Android: gives `com.google.firebase.storage.StorageException`
 * (the type `com.fluxit.data.AndroidPhotoStorage` already lets propagate unchanged - `FB-304`,
 * proven and out of this task's scope to touch) a neutral `ApplicationError` mapping, the exact
 * counterpart of iOS's `PhotoStorageIosException.toApplicationError()`
 * (`IosFirebaseStorageErrorMapping.kt`). `AndroidPhotoStorage` itself is not changed by this
 * file - a caller that *does* want the neutral form (`FB-403`) can get one from here without
 * inventing a second taxonomy.
 *
 * Maps a Firebase Storage SDK error code ([StorageException.getErrorCode]) onto the same
 * neutral [BackendErrorCode] Firestore errors already funnel through
 * (`com.fluxit.firebase.list.FirestoreErrorMapping.toBackendErrorCode`), so both backends share
 * one [RepositoryErrorCode]/[ApplicationError] taxonomy instead of inventing a second one.
 * These raw values are documented, stable `StorageException` constants - the same ones iOS's
 * `IosFirebaseStorageErrorMapping.kt` hardcodes as `Long` literals because that file cannot
 * import `FirebaseStorage` (e.g. [StorageException.ERROR_OBJECT_NOT_FOUND] == `-13010` ==
 * `STORAGE_ERROR_OBJECT_NOT_FOUND`, [StorageException.ERROR_NOT_AUTHORIZED] == `-13021` ==
 * `STORAGE_ERROR_UNAUTHORIZED`).
 *
 * [StorageException.ERROR_RETRY_LIMIT_EXCEEDED] maps to [BackendErrorCode.UNAVAILABLE] (the
 * SDK gives up after repeated transient failures, almost always connectivity) rather than a
 * new code. Codes with no clean Firestore-shaped equivalent
 * ([StorageException.ERROR_BUCKET_NOT_FOUND], [StorageException.ERROR_PROJECT_NOT_FOUND],
 * [StorageException.ERROR_INVALID_CHECKSUM], [StorageException.ERROR_CANCELED],
 * [StorageException.ERROR_UNKNOWN]) fall through to [BackendErrorCode.UNKNOWN] - a safe,
 * retryable-by-caller-policy default, matching the same fallback
 * `FirestoreErrorMapping.kt`'s `toBackendErrorCode()` already uses for its own unmapped codes.
 */
internal fun Int.toStorageBackendErrorCode(): BackendErrorCode = when (this) {
    StorageException.ERROR_OBJECT_NOT_FOUND -> BackendErrorCode.NOT_FOUND
    StorageException.ERROR_NOT_AUTHENTICATED -> BackendErrorCode.UNAUTHENTICATED
    StorageException.ERROR_NOT_AUTHORIZED -> BackendErrorCode.PERMISSION_DENIED
    StorageException.ERROR_QUOTA_EXCEEDED -> BackendErrorCode.RESOURCE_EXHAUSTED
    StorageException.ERROR_RETRY_LIMIT_EXCEEDED -> BackendErrorCode.UNAVAILABLE
    else -> BackendErrorCode.UNKNOWN
}

/** Maps [this] to FB-201's neutral [ApplicationError]. */
internal fun StorageException.toApplicationError(): ApplicationError =
    errorCode.toStorageBackendErrorCode().toRepositoryError().toApplicationError()
