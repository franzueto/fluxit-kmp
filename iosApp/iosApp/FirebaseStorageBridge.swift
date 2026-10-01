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
    private let taskLock = NSLock()
    private var tasks: [UUID: () -> Void] = [:]

    private var cancelling = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func cancelSessionTasks() async {
        await withCheckedContinuation { continuation in
            taskLock.lock()
            if tasks.isEmpty {
                taskLock.unlock()
                continuation.resume()
                return
            }
            cancelling = true
            waiters.append(continuation)
            let outgoing = Array(tasks.values)
            taskLock.unlock()
            outgoing.forEach { $0() }
        }
    }

    private func starting() -> UUID {
        let id = UUID()
        taskLock.lock(); tasks[id] = {}; taskLock.unlock()
        return id
    }

    private func track(_ id: UUID, cancellation: @escaping () -> Void) {
        taskLock.lock()
        if tasks[id] != nil {
            tasks[id] = cancellation
            let cancelNow = cancelling
            taskLock.unlock()
            if cancelNow { cancellation() }
        } else {
            taskLock.unlock()
            // Completion or cleanup raced task creation; never retain/restart it.
            cancellation()
        }
    }

    private func finished(_ id: UUID) {
        taskLock.lock()
        tasks.removeValue(forKey: id)
        let completed = tasks.isEmpty ? waiters : []
        if tasks.isEmpty { waiters.removeAll(); cancelling = false }
        taskLock.unlock()
        completed.forEach { $0.resume() }
    }


    init(storageProvider: @escaping () -> Storage = { Storage.storage() }) {
        self.storageProvider = storageProvider
        super.init()
    }

    func uploadData(photoRef: String, data: Data, mimeType: String, completion: @escaping (Error?) -> Void) {
        let metadata = StorageMetadata()
        metadata.contentType = mimeType
        let id = starting()
        let task = storage.reference(withPath: photoRef).putData(data, metadata: metadata) { [weak self] _, error in
            self?.finished(id)
            completion(error)
        }
        track(id) { task.cancel() }
    }

    func downloadData(photoRef: String, maxSize: Int64, completion: @escaping (Data?, Error?) -> Void) {
        let id = starting()
        let task = storage.reference(withPath: photoRef).getData(maxSize: maxSize) { [weak self] data, error in
            self?.finished(id)
            completion(data, error)
        }
        track(id) { task.cancel() }
    }

    func deleteObject(photoRef: String, completion: @escaping (Error?) -> Void) {
        storage.reference(withPath: photoRef).delete { error in
            completion(error)
        }
    }
}
