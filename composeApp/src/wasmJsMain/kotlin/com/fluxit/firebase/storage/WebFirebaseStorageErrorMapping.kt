package com.fluxit.firebase.storage

import com.fluxit.data.remote.ApplicationError
import com.fluxit.data.remote.BackendErrorCode
import com.fluxit.data.remote.toApplicationError
import com.fluxit.data.remote.toRepositoryError
import com.fluxit.firebase.WebBridgeError

/** The JS SDK's `StorageErrorCode.OBJECT_NOT_FOUND`, prefixed as it reaches the bridge. */
internal const val STORAGE_ERROR_OBJECT_NOT_FOUND: String = "storage/object-not-found"

/** Owner-only Rules denial (`StorageErrorCode.UNAUTHORIZED`). */
internal const val STORAGE_ERROR_UNAUTHORIZED: String = "storage/unauthorized"

internal const val STORAGE_ERROR_QUOTA_EXCEEDED: String = "storage/quota-exceeded"

internal const val STORAGE_ERROR_UNAUTHENTICATED: String = "storage/unauthenticated"

internal const val STORAGE_ERROR_RETRY_LIMIT_EXCEEDED: String = "storage/retry-limit-exceeded"

/** `true` when [this] is Storage's own "object does not exist" outcome. */
internal fun WebBridgeError.isStorageObjectNotFound(): Boolean = code == STORAGE_ERROR_OBJECT_NOT_FOUND

/**
 * Thrown inside [com.fluxit.data.WebPhotoStorage] and converted to
 * [com.fluxit.data.PhotoStorageException] before it leaves; the web counterpart of
 * `PhotoStorageIosException`.
 */
internal class PhotoStorageWebException(val error: WebBridgeError) : Exception(error.code)

/**
 * Maps a Firebase JS SDK Storage error code onto the neutral [BackendErrorCode]; the same
 * table as the iOS `storageBackendErrorCode` and Android's `toStorageBackendErrorCode()`.
 * Every other code (`storage/canceled`, `storage/bucket-not-found`, the bridge's own
 * `storage/download-size-exceeded`, a non-Storage code, …) falls through to
 * [BackendErrorCode.UNKNOWN].
 */
internal fun storageBackendErrorCode(code: String): BackendErrorCode = when (code) {
    STORAGE_ERROR_OBJECT_NOT_FOUND -> BackendErrorCode.NOT_FOUND
    STORAGE_ERROR_UNAUTHENTICATED -> BackendErrorCode.UNAUTHENTICATED
    STORAGE_ERROR_UNAUTHORIZED -> BackendErrorCode.PERMISSION_DENIED
    STORAGE_ERROR_QUOTA_EXCEEDED -> BackendErrorCode.RESOURCE_EXHAUSTED
    STORAGE_ERROR_RETRY_LIMIT_EXCEEDED -> BackendErrorCode.UNAVAILABLE
    else -> BackendErrorCode.UNKNOWN
}

internal fun PhotoStorageWebException.toApplicationError(): ApplicationError =
    storageBackendErrorCode(error.code).toRepositoryError().toApplicationError()
