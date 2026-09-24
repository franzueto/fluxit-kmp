package com.fluxit.firebase.storage

import com.fluxit.data.remote.ApplicationError
import com.fluxit.data.remote.BackendErrorCode
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.toApplicationError
import com.fluxit.data.remote.toRepositoryError
import platform.Foundation.NSError

/**
 * `FIRStorageErrorDomain`, spelled out so no Firebase symbol is referenced from Kotlin (per
 * PLAN-008, this file cannot import `FirebaseStorage`) - same discipline as
 * `com.fluxit.firebase.list.IosFirestoreErrorMapping.kt`'s `FIREBASE_FIRESTORE_ERROR_DOMAIN`.
 */
internal const val FIREBASE_STORAGE_ERROR_DOMAIN: String = "FIRStorageErrorDomain"

/**
 * `FIRStorageErrorCode.objectNotFound`'s raw value (`-13010`), from the Firebase Apple SDK's
 * public `FIRStorageErrorCode` enum - a stable, documented SDK constant, written as a literal
 * for the same reason [FIREBASE_STORAGE_ERROR_DOMAIN] is a literal string (this file cannot
 * import `FirebaseStorage`). Mirrors Android's `StorageException.ERROR_OBJECT_NOT_FOUND`
 * (`AndroidPhotoStorage`, `FB-304`) - the two SDKs surface the same backend condition through
 * different platform types.
 */
internal const val STORAGE_ERROR_OBJECT_NOT_FOUND: Long = -13010L

/**
 * `FIRStorageErrorCode.unauthorized`'s raw value (`-13021`) - the code a real owner-only
 * Storage Rules denial surfaces as, distinct from [STORAGE_ERROR_OBJECT_NOT_FOUND]. Used only
 * by [IosPhotoStorageIntegrationCheck]'s cross-user denial assertion, mirroring Android's
 * `StorageException.ERROR_NOT_AUTHORIZED` assertion in
 * `PhotoStorageEmulatorIntegrationTest.assertDenied`. Production code
 * ([com.fluxit.data.IosPhotoStorage]) never branches on this value, only on
 * [STORAGE_ERROR_OBJECT_NOT_FOUND] - per the ledger's "treat only the not-found case as
 * missing/idempotent, let any other denial/error propagate" scope.
 */
internal const val STORAGE_ERROR_UNAUTHORIZED: Long = -13021L

/**
 * `FIRStorageErrorCode.quotaExceeded`'s raw value (`-13013`) - mirrors Android's
 * `StorageException.ERROR_QUOTA_EXCEEDED`. Used only by [toApplicationError]'s mapping table.
 */
internal const val STORAGE_ERROR_QUOTA_EXCEEDED: Long = -13013L

/**
 * `FIRStorageErrorCode.unauthenticated`'s raw value (`-13020`) - mirrors Android's
 * `StorageException.ERROR_NOT_AUTHENTICATED`. Used only by [toApplicationError]'s mapping
 * table.
 */
internal const val STORAGE_ERROR_UNAUTHENTICATED: Long = -13020L

/**
 * `FIRStorageErrorCode.retryLimitExceeded`'s raw value (`-13030`) - mirrors Android's
 * `StorageException.ERROR_RETRY_LIMIT_EXCEEDED`. Used only by [toApplicationError]'s mapping
 * table.
 */
internal const val STORAGE_ERROR_RETRY_LIMIT_EXCEEDED: Long = -13030L

/** `true` when [this] is Storage's own "object does not exist" outcome. */
internal fun NSError.isStorageObjectNotFound(): Boolean =
    domain == FIREBASE_STORAGE_ERROR_DOMAIN && code == STORAGE_ERROR_OBJECT_NOT_FOUND

/** `true` when [this] is Storage's own owner-only Rules denial outcome. */
internal fun NSError.isStorageUnauthorized(): Boolean =
    domain == FIREBASE_STORAGE_ERROR_DOMAIN && code == STORAGE_ERROR_UNAUTHORIZED

/**
 * Thrown by [com.fluxit.data.IosPhotoStorage] instead of ever letting a raw Storage [NSError]
 * escape into `commonMain`-visible code - exact counterpart of
 * `com.fluxit.firebase.list.ListRepositoryException`, scoped to Storage.
 *
 * `FB-401` discharges `FB-305-NB2`: [toApplicationError] below now gives this a neutral
 * `ApplicationError` mapping, the way `ListRepositoryException` already has one. `PhotoStorage`
 * (the `commonMain` contract [com.fluxit.data.IosPhotoStorage] implements) still declares no
 * error taxonomy of its own beyond "missing returns null/no-ops, everything else propagates" -
 * this class is still thrown unchanged from every existing `IosPhotoStorage` call site (that
 * throw/catch behaviour is `FB-305`'s, proven and out of this task's scope to touch); this
 * mapping exists so a caller that *does* want the neutral form (`FB-403`) can get one without
 * inventing a second taxonomy.
 */
internal class PhotoStorageIosException(val error: NSError) : Exception(error.toString())

/**
 * Maps a Firebase Storage SDK error code (an [NSError.code] under
 * [FIREBASE_STORAGE_ERROR_DOMAIN]) onto the same neutral [BackendErrorCode] Firestore errors
 * already funnel through (`com.fluxit.firebase.list.firestoreBackendErrorCode`), so both
 * backends share one [RepositoryErrorCode]/[ApplicationError] taxonomy instead of inventing a
 * second one. Deliberately identical to Android's
 * `com.fluxit.firebase.storage.toStorageBackendErrorCode()` - both official SDKs surface the
 * same underlying Storage backend condition through different platform exception types, just
 * as the two platforms' Firestore mappings already do.
 *
 * `ERROR_RETRY_LIMIT_EXCEEDED` maps to [BackendErrorCode.UNAVAILABLE] (the SDK gives up after
 * repeated transient failures, almost always connectivity) rather than a new code. Codes with
 * no clean Firestore-shaped equivalent (`bucketNotFound`, `projectNotFound`,
 * `invalidChecksum`, `cancelled`, `unknown`) fall through to [BackendErrorCode.UNKNOWN] - a
 * safe, retryable-by-caller-policy default, matching Firestore's own unmapped-code fallback.
 */
internal fun storageBackendErrorCode(code: Long): BackendErrorCode = when (code) {
    STORAGE_ERROR_OBJECT_NOT_FOUND -> BackendErrorCode.NOT_FOUND
    STORAGE_ERROR_UNAUTHENTICATED -> BackendErrorCode.UNAUTHENTICATED
    STORAGE_ERROR_UNAUTHORIZED -> BackendErrorCode.PERMISSION_DENIED
    STORAGE_ERROR_QUOTA_EXCEEDED -> BackendErrorCode.RESOURCE_EXHAUSTED
    STORAGE_ERROR_RETRY_LIMIT_EXCEEDED -> BackendErrorCode.UNAVAILABLE
    else -> BackendErrorCode.UNKNOWN
}

/**
 * Maps [this] to FB-201's neutral [ApplicationError]. An [NSError] outside
 * [FIREBASE_STORAGE_ERROR_DOMAIN] collapses to [RepositoryErrorCode.UNKNOWN], matching
 * `IosFirestoreErrorMapping.kt`'s identical fallback for a non-Firestore-domain error.
 */
internal fun PhotoStorageIosException.toApplicationError(): ApplicationError =
    if (error.domain == FIREBASE_STORAGE_ERROR_DOMAIN) {
        storageBackendErrorCode(error.code).toRepositoryError().toApplicationError()
    } else {
        RepositoryErrorCode.UNKNOWN.toApplicationError()
    }
