import ComposeApp
import FirebaseFirestore
import Foundation

/// FB-205 Swift implementation of the Kotlin-declared `IosFirestoreItemBridge` protocol.
///
/// PLAN-008: the `FirebaseFirestore` SPM target is not cinterop-reachable from
/// `iosMain`, so this file - not Kotlin - is where every Firebase Firestore call for the
/// `users/{uid}/lists/{listId}/items` collection lives, exactly mirroring how
/// `FirebaseListBridge.swift` (FB-203) owns every call for the `lists` collection. The
/// Kotlin side (`IosFirebaseItemRepository`) owns tombstone filtering, ordering
/// (delegated to FB-201's `FirebaseDocumentMapper`), the field-scoped-patch-vs-whole-
/// document-set policy (`DEC-003d`/`DEC-003d-1`), error mapping, and - the one thing
/// this file cannot own on its own, because only Swift can open a Firestore transaction
/// - the counter *policy* itself (what counts as "was active", what delta to write),
/// which reaches this file through the synchronous `decide` callback in
/// `mutateItemWithCounters` below. This file owns nothing but translation between
/// Firestore's own types and FB-201's neutral `FirebaseValue` wire format, plus the
/// mechanical shape of each Firestore call (batch, transaction, or query).
final class FirebaseItemBridge: NSObject, IosFirestoreItemBridge {

    /// Resolved per call rather than stored, mirroring `FirebaseListBridge`/
    /// `FirebaseAuthBridge`'s `firestoreProvider`/`authProvider`.
    private let firestoreProvider: () -> Firestore

    private var firestore: Firestore { firestoreProvider() }

    init(firestoreProvider: @escaping () -> Firestore = { Firestore.firestore() }) {
        self.firestoreProvider = firestoreProvider
        super.init()
    }

    private func listDoc(uid: String, listId: String) -> DocumentReference {
        firestore.collection("users").document(uid).collection("lists").document(listId)
    }

    private func itemsCollection(uid: String, listId: String) -> CollectionReference {
        listDoc(uid: uid, listId: listId).collection("items")
    }

    func observeItems(
        uid: String,
        listId: String,
        onSnapshot: @escaping ([IosFirestoreListDocument]) -> Void,
        onError: @escaping (Error) -> Void
    ) -> any IosFirestoreListenerHandle {
        let registration = itemsCollection(uid: uid, listId: listId).addSnapshotListener { snapshot, error in
            if let error {
                onError(error)
                return
            }
            guard let snapshot else { return }
            onSnapshot(snapshot.documents.map(FirebaseItemBridge.toDocument))
        }
        return FirestoreListenerHandle(registration: registration)
    }

    /// `FB-407`: genuinely separate listener registration from `observeItems`, with
    /// `includeMetadataChanges: true` - see `FirebaseListBridge.
    /// observeListSummariesSnapshot`'s doc comment for the full rationale, identical here.
    func observeItemsSnapshot(
        uid: String,
        listId: String,
        onSnapshot: @escaping (IosFirestoreItemSnapshot) -> Void,
        onError: @escaping (Error) -> Void
    ) -> any IosFirestoreListenerHandle {
        let registration = itemsCollection(uid: uid, listId: listId).addSnapshotListener(includeMetadataChanges: true) { snapshot, error in
            if let error {
                onError(error)
                return
            }
            guard let snapshot else { return }
            onSnapshot(
                IosFirestoreItemSnapshot(
                    documents: snapshot.documents.map(FirebaseItemBridge.toDocument),
                    isFromCache: snapshot.metadata.isFromCache,
                    hasPendingWrites: snapshot.metadata.hasPendingWrites
                )
            )
        }
        return FirestoreListenerHandle(registration: registration)
    }

    func observeItem(
        uid: String,
        listId: String,
        itemId: String,
        onSnapshot: @escaping (IosFirestoreListDocument?) -> Void,
        onError: @escaping (Error) -> Void
    ) -> any IosFirestoreListenerHandle {
        let registration = itemsCollection(uid: uid, listId: listId).document(itemId).addSnapshotListener { snapshot, error in
            if let error {
                onError(error)
                return
            }
            guard let snapshot, snapshot.exists else {
                onSnapshot(nil)
                return
            }
            onSnapshot(FirebaseItemBridge.toDocument(snapshot))
        }
        return FirestoreListenerHandle(registration: registration)
    }

    /// `DEC-003d-1`: one atomic batch - a brand-new item document's whole-initial-field-
    /// set `setData(_:)`, plus an unconditional `totalItems` increment on the parent
    /// list document - mirroring `AndroidFirebaseItemRepository.addItem`'s single
    /// `WriteBatch` exactly. Plain completion-handler shape, not `async throws`, for the
    /// same runtime-safety reason `FirebaseListBridge.createList`'s KDoc documents
    /// (FB-203's crash finding: an `async throws` implementation of a Kotlin
    /// completion-handler-shaped protocol method aborts the process on a thrown error).
    func addItem(
        uid: String,
        listId: String,
        fields: [String: FirebaseValue],
        completion: @escaping (String?, Error?) -> Void
    ) {
        let itemRef = itemsCollection(uid: uid, listId: listId).document()
        let listRef = listDoc(uid: uid, listId: listId)
        let batch = firestore.batch()
        batch.setData(FirebaseItemBridge.encode(fields), forDocument: itemRef)
        batch.updateData(["totalItems": FieldValue.increment(Int64(1))], forDocument: listRef)
        batch.commit { error in
            if let error {
                completion(nil, error)
            } else {
                completion(itemRef.documentID, nil)
            }
        }
    }

    /// `DEC-003d`: field-scoped `updateData(_:)` only - never `setData(_:)`, never
    /// touching counters. Backs `updateItem`/`setPhotoPath`; Firestore's own
    /// `updateData(_:)` already throws a not-found error on a missing document, which is
    /// exactly the behavior FB-202/FB-204 established for this pair of mutations on the
    /// other platform/repository, so no special-casing is needed here.
    func updateItemFields(
        uid: String,
        listId: String,
        itemId: String,
        fields: [String: FirebaseValue],
        completion: @escaping (Error?) -> Void
    ) {
        itemsCollection(uid: uid, listId: listId).document(itemId).updateData(FirebaseItemBridge.encode(fields)) { error in
            completion(error)
        }
    }

    /// Opens a real Firestore transaction, reads the item document, hands its current
    /// fields to `decide` synchronously - Kotlin-side, pure, never suspending, safe to
    /// invoke from Firestore's own transaction-retry machinery on any thread (see
    /// `IosFirestoreItemBridge.mutateItemWithCounters`'s KDoc) - then applies whatever
    /// `ItemCounterOutcome` it returns. Uses the plain completion-handler
    /// `runTransaction(_:completion:)` overload rather than `async throws`, applied here
    /// proactively for the same class of runtime-safety reason as `addItem`/
    /// `updateItemFields` above, even though FB-203's specific crash was only reproduced
    /// for an `async throws` *implementation of a Kotlin protocol method*, not for a
    /// transaction's `updateBlock` - the safer completion-handler shape costs nothing
    /// here, so there is no reason to find out the hard way whether the same hazard
    /// extends to this call shape too.
    func mutateItemWithCounters(
        uid: String,
        listId: String,
        itemId: String,
        decide: @escaping ([String: FirebaseValue]?) -> ItemCounterOutcome,
        completion: @escaping (Error?) -> Void
    ) {
        let itemRef = itemsCollection(uid: uid, listId: listId).document(itemId)
        let listRef = listDoc(uid: uid, listId: listId)
        firestore.runTransaction({ transaction, errorPointer in
            let snapshot: DocumentSnapshot
            do {
                snapshot = try transaction.getDocument(itemRef)
            } catch {
                errorPointer?.pointee = error as NSError
                return nil
            }
            let fields: [String: FirebaseValue]? = snapshot.exists ? FirebaseItemBridge.toFields(snapshot) : nil
            let outcome = decide(fields)
            switch outcome {
            case is ItemCounterOutcomeNoOp:
                break
            case let apply as ItemCounterOutcomeApplyPatch:
                transaction.updateData(FirebaseItemBridge.encode(apply.itemFields), forDocument: itemRef)
                let delta = FirebaseItemBridge.encodeIncrements(apply.counterDelta)
                if !delta.isEmpty {
                    transaction.updateData(delta, forDocument: listRef)
                }
            case let hardDelete as ItemCounterOutcomeHardDelete:
                transaction.deleteDocument(itemRef)
                let delta = FirebaseItemBridge.encodeIncrements(hardDelete.counterDelta)
                if !delta.isEmpty {
                    transaction.updateData(delta, forDocument: listRef)
                }
            default:
                // Unreachable: ItemCounterOutcome is a Kotlin sealed interface, every
                // case is covered above. Mirrors FirebaseListBridge.encode's the same
                // defensive-not-crashing fallback for a hypothetical future case.
                break
            }
            return nil
        }, completion: { _, error in
            completion(error)
        })
    }

    /// One page of the `clearCompleted` sweep: queries up to `chunkSize` active-and-
    /// completed items, tombstones them and decrements both list counters by the page's
    /// real size, all in one atomic `WriteBatch` - mirrors
    /// `AndroidFirebaseItemRepository.clearCompleted`'s per-chunk batch exactly.
    /// `IosFirebaseItemRepository` owns the re-query-until-empty loop and the runaway-
    /// iteration guard; this method only ever handles one page and reports how many
    /// documents it touched (`0` tells the caller the sweep is done).
    func clearCompletedChunk(
        uid: String,
        listId: String,
        chunkSize: Int32,
        itemPatch: [String: FirebaseValue],
        completion: @escaping (KotlinInt, Error?) -> Void
    ) {
        // Note: `chunkSize` (a plain method parameter) crosses from Kotlin's `Int` as an
        // unboxed `Int32`, but the *count* below - a primitive appearing inside a Kotlin
        // function-type (the `completion` closure's own parameter) - crosses boxed, as
        // `KotlinInt`, not `Int32`. Confirmed by the generated `ComposeApp.h` header
        // during this task's own build (a real, empirically-found interop asymmetry, not
        // an assumption): a Kotlin primitive as a closure parameter is boxed even though
        // the identical primitive as a direct method parameter is not.
        let query = itemsCollection(uid: uid, listId: listId)
            .whereField("isCompleted", isEqualTo: true)
            .whereField("deletedAt", isEqualTo: NSNull())
            .limit(to: Int(chunkSize))
        query.getDocuments { snapshot, error in
            if let error {
                completion(KotlinInt(int: 0), error)
                return
            }
            guard let snapshot, !snapshot.documents.isEmpty else {
                completion(KotlinInt(int: 0), nil)
                return
            }
            let count = snapshot.documents.count
            let batch = self.firestore.batch()
            let encodedPatch = FirebaseItemBridge.encode(itemPatch)
            for document in snapshot.documents {
                batch.updateData(encodedPatch, forDocument: document.reference)
            }
            batch.updateData(
                [
                    "totalItems": FieldValue.increment(Int64(-count)),
                    "completedItems": FieldValue.increment(Int64(-count)),
                ],
                forDocument: self.listDoc(uid: uid, listId: listId)
            )
            batch.commit { error in
                completion(KotlinInt(int: Int32(count)), error)
            }
        }
    }

    // MARK: - FB-201 FirebaseValue <-> Firestore's own value types
    //
    // Deliberately duplicated from `FirebaseListBridge.swift`'s private statics rather
    // than shared, exactly as `FirebaseListBridge.swift` itself is not shared with
    // `FirebaseAuthBridge.swift` - this codebase has no common Swift utility module, and
    // each bridge file stays as thin and self-contained as FB-103/FB-203 established.

    private static func encode(_ fields: [String: FirebaseValue]) -> [String: Any] {
        fields.mapValues(encode)
    }

    private static func encode(_ value: FirebaseValue) -> Any {
        switch value {
        case is FirebaseValueNull:
            return NSNull()
        case let text as FirebaseValueText:
            return text.value
        case let bool as FirebaseValueBool:
            return bool.value
        case let number as FirebaseValueNumber:
            return number.value
        case let timestamp as FirebaseValueTimestamp:
            return Timestamp(date: Date(timeIntervalSince1970: Double(timestamp.epochMillis) / 1000.0))
        case is FirebaseValuePendingServerTimestamp:
            return FieldValue.serverTimestamp()
        default:
            // Unreachable: FirebaseValue is a Kotlin sealed interface, so every case is
            // covered above. Same defensive fallback as FirebaseListBridge.encode.
            return NSNull()
        }
    }

    /// `counterDelta`'s value type is FB-201's own `FirebaseValue.Number` (see
    /// `IosFirestoreItemBridge.kt`'s `ItemCounterOutcome` KDoc for why), so unwrapping
    /// it to a plain `Int64` for `FieldValue.increment` is exactly as safe as
    /// `encode`'s `FirebaseValueNumber` case above - no separate boxed-numeric-type
    /// concern to reason about.
    private static func encodeIncrements(_ counterDelta: [String: FirebaseValueNumber]) -> [String: Any] {
        counterDelta.mapValues { FieldValue.increment($0.value) }
    }

    private static func toDocument(_ snapshot: DocumentSnapshot) -> IosFirestoreListDocument {
        IosFirestoreListDocument(id: snapshot.documentID, fields: toFields(snapshot))
    }

    private static func toFields(_ snapshot: DocumentSnapshot) -> [String: FirebaseValue] {
        var fields: [String: FirebaseValue] = [:]
        for (key, raw) in snapshot.data() ?? [:] {
            if let decoded = decode(raw) {
                fields[key] = decoded
            }
        }
        return fields
    }

    /// Same `CFGetTypeID`/`CFBooleanGetTypeID` `NSNumber`/`Bool` disambiguation as
    /// `FirebaseListBridge.decode` - an SDK-wide concern (any `NSNumber`-backed field,
    /// not a list-specific one; item documents have their own 0/1-valued integer field,
    /// `schemaVersion`, exposed to exactly the same mis-decoding risk FB-203 found for
    /// lists). A field whose SDK value type this bridge does not itself write is dropped
    /// as if missing, matching Android's `FirestoreValueCodec.decodeValue`.
    private static func decode(_ raw: Any) -> FirebaseValue? {
        switch raw {
        case is NSNull:
            return FirebaseValueNull.shared
        case let value as String:
            return FirebaseValueText(value: value)
        case let number as NSNumber:
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                return FirebaseValueBool(value: number.boolValue)
            }
            return FirebaseValueNumber(value: number.int64Value)
        case let value as Timestamp:
            return FirebaseValueTimestamp(epochMillis: Int64(value.dateValue().timeIntervalSince1970 * 1000.0))
        default:
            return nil
        }
    }
}
