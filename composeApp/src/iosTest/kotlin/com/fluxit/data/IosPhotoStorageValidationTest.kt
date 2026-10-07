package com.fluxit.data

import com.fluxit.firebase.list.CurrentUidProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * [PhotoStorage.uploadPhoto] documents [PhotoRejected] for a direct caller passing invalid
 * bytes. The bridge here fails the test if it is ever reached: validation must reject the
 * bytes before anything is sent to Storage.
 */
class IosPhotoStorageValidationTest {

    private val storage = IosPhotoStorage(
        bridgeProvider = { error("a rejected upload must not reach the Storage bridge") },
        currentUid = CurrentUidProvider { "uid-1" },
    )

    @Test
    fun uploadPhoto_corruptBytes_throwsPhotoRejectedCorrupt() = runTest {
        assertFailsWith<PhotoRejected.Corrupt> { storage.uploadPhoto("item-1", byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun uploadPhoto_unsupportedType_throwsPhotoRejectedUnsupportedType() = runTest {
        assertFailsWith<PhotoRejected.UnsupportedType> {
            storage.uploadPhoto("item-1", "GIF89a".encodeToByteArray() + ByteArray(16))
        }
    }

    @Test
    fun uploadPhoto_oversizedBytes_throwsPhotoRejectedTooLarge() = runTest {
        assertFailsWith<PhotoRejected.TooLarge> {
            storage.uploadPhoto("item-1", ByteArray(PhotoPolicy.MAX_SOURCE_BYTES + 1))
        }
    }
}
