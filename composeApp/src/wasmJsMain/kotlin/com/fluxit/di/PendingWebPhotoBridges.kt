package com.fluxit.di

import com.fluxit.data.PhotoContent
import com.fluxit.data.PhotoPicker
import com.fluxit.data.PhotoStorage
import com.fluxit.data.PhotoStorageException
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.toApplicationError

/**
 * Phase 3 placeholders so item detail opens before photos are bridged: picking does
 * nothing (as if cancelled), storage calls fail with a retryable error. Replaced by
 * `WebPhotoPicker` and `WebPhotoStorage` in Phase 4 (docs/web-app/PROGRESS.md).
 */
internal class PendingWebPhotoPicker : PhotoPicker {
    override suspend fun pickPhoto(): ByteArray? = null
}

/** See [PendingWebPhotoPicker]. */
internal class PendingWebPhotoStorage : PhotoStorage {
    override suspend fun uploadPhoto(itemId: String, bytes: ByteArray): String = unavailable()
    override suspend fun loadPhoto(photoRef: String): PhotoContent? = unavailable()
    override suspend fun deletePhoto(photoRef: String): Unit = unavailable()
}

private fun unavailable(): Nothing = throw PhotoStorageException(RepositoryErrorCode.UNKNOWN.toApplicationError())
