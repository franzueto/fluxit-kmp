import ComposeApp
import FirebaseFirestore
import Foundation

/// FB-203 Swift implementation of the Kotlin-declared `IosFirestoreListBridge` protocol.
///
/// PLAN-008: the `FirebaseFirestore` SPM target is not cinterop-reachable from
/// `iosMain`, so this file - not Kotlin - is where every Firebase Firestore call for the
/// `users/{uid}/lists` collection lives, exactly mirroring how `FirebaseAuthBridge.swift`
/// (FB-103) owns every Firebase Auth call. The Kotlin side (`IosFirebaseListRepository`)
/// owns tombstone filtering, ordering (both delegated to FB-201's
/// `FirebaseDocumentMapper`), the field-scoped-patch-vs-whole-document-set policy
/// (`DEC-003d`/`DEC-003d-1`), and error mapping; this file owns nothing but translation
/// between Firestore's own types and FB-201's neutral `FirebaseValue` wire format.
///
/// Deliberately as thin as `FirebaseAuthBridge.swift`: this is the part that cannot be
/// unit-tested from Gradle, so there should be as little logic in it as possible.
/// Reusing `FirebaseValue` itself as the cross-boundary field type (rather than
/// inventing a second `Any?`-based encoding) is what keeps this file's only real logic
/// to two small, exhaustive `switch` statements.
final class FirebaseListBridge: NSObject, IosFirestoreListBridge {

    /// Resolved per call rather than stored, mirroring `FirebaseAuthBridge`'s
    /// `authProvider` - merely registering the bridge at launch does not instantiate
    /// `Firestore` for the default app.
    private let firestoreProvider: () -> Firestore

    private var firestore: Firestore { firestoreProvider() }

    init(firestoreProvider: @escaping () -> Firestore = { Firestore.firestore() }) {
        self.firestoreProvider = firestoreProvider
        super.init()
    }

    private func listsCollection(uid: String) -> CollectionReference {
        firestore.collection("users").document(uid).collection("lists")
    }

    func observeListSummaries(
        uid: String,
        onSnapshot: @escaping ([IosFirestoreListDocument]) -> Void,
        onError: @escaping (Error) -> Void
    ) -> any IosFirestoreListenerHandle {
        let registration = listsCollection(uid: uid).addSnapshotListener { snapshot, error in
            if let error {
                onError(error)
                return
            }
            guard let snapshot else { return }
            onSnapshot(snapshot.documents.map(FirebaseListBridge.toDocument))
        }
        return FirestoreListenerHandle(registration: registration)
    }

    /// `FB-407`: genuinely separate listener registration from `observeListSummaries`,
    /// with `includeMetadataChanges: true` so a metadata-only transition (a locally
    /// cached write finally getting server-acknowledged, with no field change) re-fires
    /// this listener - the default `includeMetadataChanges: false` `observeListSummaries`
    /// registration never does, by Firestore's own documented behavior, which is exactly
    /// why this is not shared with it. See `IosFirestoreListBridge.
    /// observeListSummariesSnapshot`'s KDoc for the full rationale.
    func observeListSummariesSnapshot(
        uid: String,
        onSnapshot: @escaping (IosFirestoreListSnapshot) -> Void,
        onError: @escaping (Error) -> Void
    ) -> any IosFirestoreListenerHandle {
        let registration = listsCollection(uid: uid).addSnapshotListener(includeMetadataChanges: true) { snapshot, error in
            if let error {
                onError(error)
                return
            }
            guard let snapshot else { return }
            onSnapshot(
                IosFirestoreListSnapshot(
                    documents: snapshot.documents.map(FirebaseListBridge.toDocument),
                    isFromCache: snapshot.metadata.isFromCache,
                    hasPendingWrites: snapshot.metadata.hasPendingWrites
                )
            )
        }
        return FirestoreListenerHandle(registration: registration)
    }

    func observeList(
        uid: String,
        listId: String,
        onSnapshot: @escaping (IosFirestoreListDocument?) -> Void,
        onError: @escaping (Error) -> Void
    ) -> any IosFirestoreListenerHandle {
        let registration = listsCollection(uid: uid).document(listId).addSnapshotListener { snapshot, error in
            if let error {
                onError(error)
                return
            }
            guard let snapshot, snapshot.exists else {
                onSnapshot(nil)
                return
            }
            onSnapshot(FirebaseListBridge.toDocument(snapshot))
        }
        return FirestoreListenerHandle(registration: registration)
    }

    /// `DEC-003d-1`: whole-document `setData(_:)` on a brand-new auto-ID document only.
    ///
    /// Deliberately a plain completion-handler implementation, not `async throws`: an
    /// earlier version of this method used `async throws` (which also satisfies this
    /// protocol requirement at compile time, since Swift auto-generates an async
    /// alternative for eligible Objective-C completion-handler shapes), but the
    /// emulator-backed integration check (`IosFirestoreListIntegrationCheck`) proved that
    /// path unsafe at runtime: Kotlin/Native's own generated bridge from a Swift `async
    /// throws` implementation back to this completion-handler-shaped Kotlin interface
    /// method aborted the process (`Kotlin_ObjCExport_runCompletionFailure` ->
    /// `terminateWithUnhandledException`) instead of invoking `completion(nil, error)` when
    /// Firestore actually threw - reproduced with a genuine cross-user `PERMISSION_DENIED`
    /// against the real emulator. A hand-written completion callback, exactly matching
    /// `FirebaseAuthBridge.swift`'s proven shape, does not go through that machinery.
    func createList(
        uid: String,
        fields: [String: FirebaseValue],
        completion: @escaping (String?, Error?) -> Void
    ) {
        let reference = listsCollection(uid: uid).document()
        reference.setData(FirebaseListBridge.encode(fields)) { error in
            if let error {
                completion(nil, error)
            } else {
                completion(reference.documentID, nil)
            }
        }
    }

    /// `DEC-003d`: field-scoped `updateData(_:)` - never `setData(_:)`. Firestore's own
    /// field-mask semantics mean only the keys present in `fields` are ever touched. Same
    /// plain-completion-handler shape as [createList], for the same runtime-safety reason.
    func updateListFields(
        uid: String,
        listId: String,
        fields: [String: FirebaseValue],
        completion: @escaping (Error?) -> Void
    ) {
        listsCollection(uid: uid).document(listId).updateData(FirebaseListBridge.encode(fields)) { error in
            completion(error)
        }
    }

    // MARK: - FB-201 FirebaseValue <-> Firestore's own value types

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
            // covered above. Falling back to NSNull rather than crashing if a future
            // FB-201 case is ever added and this file forgotten.
            return NSNull()
        }
    }

    private static func toDocument(_ snapshot: DocumentSnapshot) -> IosFirestoreListDocument {
        var fields: [String: FirebaseValue] = [:]
        // FB-701: local server-timestamp estimates keep offline creations visible;
        // explicit stored nulls remain null and are rejected by required-field mapping.
        for (key, raw) in snapshot.data(with: .estimate) ?? [:] {
            if let decoded = decode(raw) {
                fields[key] = decoded
            }
        }
        return IosFirestoreListDocument(id: snapshot.documentID, fields: fields)
    }

    /// A field whose SDK value type this bridge does not itself write (for example a
    /// `GeoPoint` some other client wrote) is dropped as if missing, matching Android's
    /// `FirestoreValueCodec.decodeValue`: FB-201's mapper then reports `MISSING_FIELD`,
    /// a safe, always-defined outcome for a shape this repository never produces itself.
    ///
    /// FB-203 empirical finding, reproduced against the real Firestore emulator: a plain
    /// `case let value as Bool` / `case let value as Int64` switch is NOT safe here.
    /// `Bool` bridges from *any* `NSNumber` whose value is 0 or 1, so an integer field
    /// like `schemaVersion` (1) or `totalItems`/`completedItems` (0 on a fresh list) was
    /// silently mis-decoded as `FirebaseValueBool` instead of `FirebaseValueNumber` -
    /// which FB-201's `FirebaseDocumentMapper` then correctly rejected as malformed
    /// (`WRONG_TYPE`), causing every freshly created list to be dropped from
    /// `observeListSummaries` forever, not just transiently. `CFGetTypeID` distinguishes
    /// a genuine `CFBoolean`-backed `NSNumber` from a numeric one precisely, which a
    /// Swift `as?` cast cannot.
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

/// Releases one Firestore snapshot listener. Idempotent, exactly like
/// `FirebaseAuthListenerHandle` (FB-103): a double `remove()` must not remove a *later*
/// listener registered by a different collector.
final class FirestoreListenerHandle: NSObject, IosFirestoreListenerHandle {

    private var registration: ListenerRegistration?

    init(registration: ListenerRegistration) {
        self.registration = registration
        super.init()
    }

    func remove() {
        guard let registration else { return }
        self.registration = nil
        registration.remove()
    }
}
