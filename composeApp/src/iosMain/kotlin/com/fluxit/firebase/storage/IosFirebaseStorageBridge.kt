package com.fluxit.firebase.storage

import platform.Foundation.NSData
import platform.Foundation.NSError

/**
 * The Swift-implemented seam through which the iOS [com.fluxit.data.IosPhotoStorage]
 * reaches Cloud Storage (the Swift-only Firebase boundary on iOS: the `FirebaseStorage` SPM target is not cinterop-reachable
 * from `iosMain` - `FirebaseBootstrap.swift`'s own KDoc already states this - so every
 * Firebase Storage call lives in Swift, `iosApp/iosApp/FirebaseStorageBridge.swift`, exactly
 * mirroring how [com.fluxit.firebase.auth.IosAuthBridge],
 * [com.fluxit.firebase.list.IosFirestoreListBridge], and
 * [com.fluxit.firebase.item.IosFirestoreItemBridge] each own their own SDK surface).
 *
 * Every call is addressed by the exact `photoRef` string
 * [com.fluxit.data.remote.FirebaseSchema.photoRef] already produces - this bridge never
 * constructs or parses that shape, mirroring [com.fluxit.data.PhotoStorage]'s documented
 * boundary.
 *
 * Payloads cross as [NSData], not a Kotlin `ByteArray`: `NSData` is a Foundation type,
 * directly cinterop-reachable from `iosMain` on both sides of this boundary (unlike
 * `FirebaseStorage`'s own types), and `PhotoBridges.ios.kt` already builds/consumes one from
 * a `ByteArray` (the interim stub's `NSData.dataWithBytes`/`NSData.toByteArray`
 * pair, reused unmodified here rather than inventing a second Kotlin-Swift byte-crossing
 * convention).
 *
 * Every completion handler must be invoked exactly once, on any thread. `null` means success,
 * mirroring [com.fluxit.firebase.auth.IosAuthBridge]'s convention exactly.
 */
interface IosFirebaseStorageBridge {

    /** Uploads [data] to the object at [photoRef], creating it (or overwriting it, for the
     * raw cross-user-denial check only - production [com.fluxit.data.IosPhotoStorage] never
     * calls this against an existing [photoRef]; see [com.fluxit.data.PhotoStorage.uploadPhoto]'s
     * "always creates a new object" contract). [mimeType] is derived from the validated
     * image bytes and attached to the SDK upload as Storage metadata. */
    fun uploadData(photoRef: String, data: NSData, mimeType: String, completion: (NSError?) -> Unit)

    /**
     * Downloads the full contents of the object at [photoRef], capped at [maxSize] bytes -
     * the Storage SDK requires an explicit ceiling to avoid an unbounded in-memory download,
     * same [com.fluxit.data.AndroidPhotoStorage] precedent this mirrors. On
     * success, `data` is non-null and `error` is null; on failure (including the object not
     * existing, or an owner-only Rules denial), `data` is null and `error` is non-null.
     */
    fun downloadData(photoRef: String, maxSize: Long, completion: (NSData?, NSError?) -> Unit)

    /** Deletes the object at [photoRef]. */
    fun deleteObject(photoRef: String, completion: (NSError?) -> Unit)
}

/**
 * Hand-off point between the Swift app layer and the Kotlin framework, exactly mirroring
 * [com.fluxit.firebase.item.IosFirestoreItemBridgeRegistry].
 */
object IosFirebaseStorageBridgeRegistry {

    private var registered: IosFirebaseStorageBridge? = null

    /** Called once from Swift, from `FirebaseBootstrap.start()`. */
    fun register(bridge: IosFirebaseStorageBridge) {
        registered = bridge
    }

    /** Test/diagnostic accessor; `null` before the app layer has registered. */
    fun bridgeOrNull(): IosFirebaseStorageBridge? = registered

    internal fun requireBridge(): IosFirebaseStorageBridge = checkNotNull(registered) {
        "No IosFirebaseStorageBridge registered. FirebaseBootstrap.start() must run - and " +
            "must call IosFirebaseStorageBridgeRegistry.register - before PhotoStorage is " +
            "resolved."
    }
}
