package com.fluxit.firebase.storage

import com.fluxit.data.toByteArray
import com.fluxit.data.toUint8Array
import com.fluxit.firebase.WebBridgeError
import com.fluxit.firebase.WebFirebase
import com.fluxit.firebase.storageDelete
import com.fluxit.firebase.storageDownload
import com.fluxit.firebase.storageUpload
import com.fluxit.firebase.toWebBridgeError

/**
 * The seam through which [com.fluxit.data.WebPhotoStorage] reaches Cloud Storage; same
 * methods as the iOS `IosFirebaseStorageBridge`. Every call is addressed by the exact
 * `photoRef` string [com.fluxit.data.remote.FirebaseSchema.photoRef] produces - this bridge
 * never constructs or parses that shape.
 *
 * Every completion is invoked exactly once. `null` means success.
 */
internal interface WebFirebaseStorageBridge {

    /** Uploads [bytes] as the object at [photoRef], with [mimeType] as its content type. */
    fun uploadData(photoRef: String, bytes: ByteArray, mimeType: String, completion: (WebBridgeError?) -> Unit)

    /**
     * Downloads the object at [photoRef], failing if it is larger than [maxSize] bytes. On
     * success the bytes are non-null and the error is null; on failure (including a missing
     * object or an owner-only Rules denial) the bytes are null and the error is non-null.
     */
    fun downloadData(photoRef: String, maxSize: Long, completion: (ByteArray?, WebBridgeError?) -> Unit)

    /** Deletes the object at [photoRef]. */
    fun deleteObject(photoRef: String, completion: (WebBridgeError?) -> Unit)
}

/** [WebFirebaseStorageBridge] over `firebase-bridge.mjs`. Starts Firebase on first use. */
internal object JsWebFirebaseStorageBridge : WebFirebaseStorageBridge {

    override fun uploadData(photoRef: String, bytes: ByteArray, mimeType: String, completion: (WebBridgeError?) -> Unit) {
        WebFirebase.ensureStarted()
        storageUpload(photoRef, bytes.toUint8Array(), mimeType) { completion(it?.toWebBridgeError()) }
    }

    override fun downloadData(photoRef: String, maxSize: Long, completion: (ByteArray?, WebBridgeError?) -> Unit) {
        WebFirebase.ensureStarted()
        storageDownload(photoRef, maxSize.toDouble()) { buffer, error ->
            completion(buffer?.toByteArray(), error?.toWebBridgeError())
        }
    }

    override fun deleteObject(photoRef: String, completion: (WebBridgeError?) -> Unit) {
        WebFirebase.ensureStarted()
        storageDelete(photoRef) { completion(it?.toWebBridgeError()) }
    }
}
