import ComposeApp
import FirebaseStorage
import Foundation

/// FB-305 Swift implementation of the Kotlin-declared `IosFirebaseStorageBridge` protocol.
///
/// PLAN-008: `FirebaseStorage` is a Swift-only SPM module cinterop cannot consume, so this
/// file - not Kotlin - is where every Firebase Storage call for a `photoRef`-addressed
/// object lives, exactly mirroring how `FirebaseAuthBridge.swift` (FB-103),
/// `FirebaseListBridge.swift` (FB-203), and `FirebaseItemBridge.swift` (FB-205) each own
/// their own SDK surface. This file owns nothing but translation between raw bytes and a
/// `StorageReference` addressed by `photoRef` verbatim - no path construction, no
/// upload/resize/validation policy (that is entirely `FB-303`'s `PhotoPolicy.kt`/
/// `ImageTransform.ios.kt`, upstream of this file, in Kotlin).
final class FirebaseStorageBridge: NSObject, IosFirebaseStorageBridge {

    /// Resolved per call rather than stored, mirroring `FirebaseItemBridge`/
    /// `FirebaseListBridge`'s `firestoreProvider`.
    private let storageProvider: () -> Storage

    private var storage: Storage { storageProvider() }

    init(storageProvider: @escaping () -> Storage = { Storage.storage() }) {
        self.storageProvider = storageProvider
        super.init()
    }

    func uploadData(photoRef: String, data: Data, completion: @escaping (Error?) -> Void) {
        storage.reference(withPath: photoRef).putData(data, metadata: nil) { _, error in
            completion(error)
        }
    }

    func downloadData(photoRef: String, maxSize: Int64, completion: @escaping (Data?, Error?) -> Void) {
        storage.reference(withPath: photoRef).getData(maxSize: maxSize) { data, error in
            completion(data, error)
        }
    }

    func deleteObject(photoRef: String, completion: @escaping (Error?) -> Void) {
        storage.reference(withPath: photoRef).delete { error in
            completion(error)
        }
    }
}
