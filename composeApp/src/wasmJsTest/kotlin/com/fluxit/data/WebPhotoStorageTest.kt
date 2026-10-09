package com.fluxit.data

import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.firebase.WebBridgeError
import com.fluxit.firebase.list.CurrentUidProvider
import com.fluxit.firebase.storage.STORAGE_ERROR_OBJECT_NOT_FOUND
import com.fluxit.firebase.storage.STORAGE_ERROR_UNAUTHORIZED
import com.fluxit.firebase.storage.WebFirebaseStorageBridge
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * [WebPhotoStorage] over a scripted bridge: the iOS validation cases
 * (`IosPhotoStorageValidationTest`) plus addressing, MIME type, missing-object and
 * error-wrapping behaviour.
 */
class WebPhotoStorageTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    private class ScriptedBridge(
        var uploadError: WebBridgeError? = null,
        var download: Pair<ByteArray?, WebBridgeError?> = null to null,
        var deleteError: WebBridgeError? = null,
    ) : WebFirebaseStorageBridge {
        val calls = mutableListOf<String>()

        override fun uploadData(photoRef: String, bytes: ByteArray, mimeType: String, completion: (WebBridgeError?) -> Unit) {
            calls += "upload $photoRef $mimeType ${bytes.size}"
            completion(uploadError)
        }

        override fun downloadData(photoRef: String, maxSize: Long, completion: (ByteArray?, WebBridgeError?) -> Unit) {
            calls += "download $photoRef $maxSize"
            completion(download.first, download.second)
        }

        override fun deleteObject(photoRef: String, completion: (WebBridgeError?) -> Unit) {
            calls += "delete $photoRef"
            completion(deleteError)
        }
    }

    private fun storage(bridge: WebFirebaseStorageBridge) =
        WebPhotoStorage({ bridge }, CurrentUidProvider { "uid-1" }, { "photo-1" })

    private val rejectingStorage = WebPhotoStorage(
        { error("a rejected upload must not reach the Storage bridge") },
        CurrentUidProvider { "uid-1" },
        { "photo-1" },
    )

    @Test
    fun uploadPhoto_corruptBytes_throwsPhotoRejectedCorrupt() = runTest {
        assertFailsWith<PhotoRejected.Corrupt> { rejectingStorage.uploadPhoto("item-1", byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun uploadPhoto_unsupportedType_throwsPhotoRejectedUnsupportedType() = runTest {
        assertFailsWith<PhotoRejected.UnsupportedType> {
            rejectingStorage.uploadPhoto("item-1", "GIF89a".encodeToByteArray() + ByteArray(16))
        }
    }

    @Test
    fun uploadPhoto_oversizedBytes_throwsPhotoRejectedTooLarge() = runTest {
        assertFailsWith<PhotoRejected.TooLarge> {
            rejectingStorage.uploadPhoto("item-1", ByteArray(PhotoPolicy.MAX_SOURCE_BYTES + 1))
        }
    }

    @Test
    fun uploadPhoto_sendsTheBytesToANewOwnerScopedRefWithTheSniffedType() = runTest {
        val bridge = ScriptedBridge()

        val ref = storage(bridge).uploadPhoto("item-1", jpeg)

        assertEquals("users/uid-1/items/item-1/photo-1", ref)
        assertEquals(listOf("upload users/uid-1/items/item-1/photo-1 image/jpeg ${jpeg.size}"), bridge.calls)
    }

    @Test
    fun uploadPhoto_failureIsWrappedNotLeaked() = runTest {
        val bridge = ScriptedBridge(uploadError = WebBridgeError(STORAGE_ERROR_UNAUTHORIZED, "denied"))

        val failure = assertFailsWith<PhotoStorageException> { storage(bridge).uploadPhoto("item-1", jpeg) }

        assertEquals(RepositoryErrorCode.FORBIDDEN, failure.error.code)
    }

    @Test
    fun loadPhoto_returnsTheDownloadedBytesWithinTheUploadLimit() = runTest {
        val bridge = ScriptedBridge(download = jpeg to null)

        val content = assertIs<PhotoContent.Bytes>(storage(bridge).loadPhoto("users/uid-1/items/item-1/photo-1"))

        assertContentEquals(jpeg, content.bytes)
        assertEquals(listOf("download users/uid-1/items/item-1/photo-1 ${PhotoPolicy.MAX_UPLOAD_BYTES}"), bridge.calls)
    }

    @Test
    fun loadPhoto_missingObjectIsNull() = runTest {
        val bridge = ScriptedBridge(download = null to WebBridgeError(STORAGE_ERROR_OBJECT_NOT_FOUND, "gone"))

        assertNull(storage(bridge).loadPhoto("users/uid-1/items/item-1/photo-1"))
    }

    @Test
    fun loadPhoto_otherFailuresAreWrapped() = runTest {
        val denied = ScriptedBridge(download = null to WebBridgeError(STORAGE_ERROR_UNAUTHORIZED, "denied"))
        val empty = ScriptedBridge(download = null to null)

        assertEquals(
            RepositoryErrorCode.FORBIDDEN,
            assertFailsWith<PhotoStorageException> { storage(denied).loadPhoto("ref") }.error.code,
        )
        assertEquals(
            RepositoryErrorCode.UNKNOWN,
            assertFailsWith<PhotoStorageException> { storage(empty).loadPhoto("ref") }.error.code,
        )
    }

    @Test
    fun deletePhoto_isIdempotentForAMissingObject() = runTest {
        val bridge = ScriptedBridge(deleteError = WebBridgeError(STORAGE_ERROR_OBJECT_NOT_FOUND, "gone"))

        storage(bridge).deletePhoto("users/uid-1/items/item-1/photo-1")

        assertEquals(listOf("delete users/uid-1/items/item-1/photo-1"), bridge.calls)
    }

    @Test
    fun deletePhoto_otherFailuresAreWrapped() = runTest {
        val bridge = ScriptedBridge(deleteError = WebBridgeError("storage/retry-limit-exceeded", "offline"))

        val failure = assertFailsWith<PhotoStorageException> { storage(bridge).deletePhoto("ref") }

        assertEquals(RepositoryErrorCode.OFFLINE, failure.error.code)
        assertTrue(failure.error.canRetry)
    }
}
