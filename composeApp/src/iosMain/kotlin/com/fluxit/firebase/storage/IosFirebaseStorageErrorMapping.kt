package com.fluxit.firebase.storage

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

/** `true` when [this] is Storage's own "object does not exist" outcome. */
internal fun NSError.isStorageObjectNotFound(): Boolean =
    domain == FIREBASE_STORAGE_ERROR_DOMAIN && code == STORAGE_ERROR_OBJECT_NOT_FOUND

/** `true` when [this] is Storage's own owner-only Rules denial outcome. */
internal fun NSError.isStorageUnauthorized(): Boolean =
    domain == FIREBASE_STORAGE_ERROR_DOMAIN && code == STORAGE_ERROR_UNAUTHORIZED

/**
 * Thrown by [com.fluxit.data.IosPhotoStorage] instead of ever letting a raw Storage [NSError]
 * escape into `commonMain`-visible code - exact counterpart of
 * `com.fluxit.firebase.list.ListRepositoryException`, scoped to Storage. Deliberately not
 * further classified into a neutral `ApplicationError` the way `ListRepositoryException` is:
 * `PhotoStorage` (the `commonMain` contract this implements) declares no error taxonomy of
 * its own beyond "missing returns null/no-ops, everything else propagates" - there is no
 * existing neutral Storage error type to map onto, and inventing one is explicitly out of
 * this task's scope (Phase 4's error-type work, not Phase 3's).
 */
internal class PhotoStorageIosException(val error: NSError) : Exception(error.toString())
