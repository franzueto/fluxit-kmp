#if DEBUG

import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import FirebaseStorage
import Foundation
import UIKit

/// FB-008 TEMPORARY Phase 0 smoke harness (iOS-simulator leg).
///
/// ## Why this exists
///
/// `FB-007` proved that the Firebase Apple SDK links and that `FirebaseApp.configure()`
/// is reached in source order, but no FluxIt app had ever been launched on any target,
/// so the runtime was unproven. This is the iOS half of the two-platform smoke matrix
/// Phase 0's exit criterion requires. Per `DEC-004` the iOS target is the simulator.
///
/// It runs against the **live development Firebase project**, not the emulator suite
/// (`fluxit.firebase.emulator.enabled` stays `false`).
///
/// ## Why it lives in Swift
///
/// `PLAN-008`: `FirebaseStorage` and the modern `FirebaseAuth` are Swift modules that
/// Kotlin/Native cinterop cannot consume, so all Firebase-touching iOS code lives here
/// in `iosApp/iosApp/`, never in `iosMain`. This file imports no Kotlin framework
/// symbols at all, so it adds no new coupling across the framework boundary.
///
/// ## How it is isolated, and how `FB-009` removes it
///
/// The whole file is inside `#if DEBUG`, so it does not exist in a Release build. It
/// is opt-in even in Debug: it only runs when the process is launched with the
/// `-FluxItFirebaseSmoke` argument, which nothing but a deliberate
/// `xcrun simctl launch ... --args -FluxItFirebaseSmoke YES` supplies. It touches no
/// repository, no Koin graph and no Compose state. `FB-009` removes it by deleting
/// this file, its two `project.pbxproj` entries, and the five-line `#if DEBUG` block in
/// `FirebaseBootstrap.swift`'s `AppDelegate`.
///
/// ## Live cross-user denial preflight (hard requirement, `FB-008`)
///
/// `MAN-002` (the owner-only Rules deploy) is a user self-report no agent has verified.
/// Before writing any real data this harness creates two throwaway accounts and, signed
/// in as B, attempts read *and* write against A's Firestore and Storage paths; every
/// attempt must be rejected. A later sweep repeats the denial against resources that
/// actually exist, which removes the "denied, or merely absent?" ambiguity.
///
/// ## How to run it (and the one non-obvious build flag)
///
/// ```sh
/// xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
///     -destination "platform=iOS Simulator,name=iPhone 17" -derivedDataPath <dd> \
///     CODE_SIGN_IDENTITY="-" CODE_SIGN_STYLE=Manual \
///     DEVELOPMENT_TEAM="" PROVISIONING_PROFILE_SPECIFIER="" build
/// xcrun simctl install <udid> <dd>/Build/Products/Debug-iphonesimulator/FluxIt.app
/// xcrun simctl launch --console-pty <udid> com.fluxit.FluxIt -FluxItFirebaseSmoke YES
/// ```
///
/// The ad-hoc signing flags are load-bearing and this is a real finding for `FB-103`:
/// `FB-007`'s `CODE_SIGNING_ALLOWED=NO` is fine for a link-only build, but an unsigned
/// simulator app has no keychain entitlement, so FirebaseAuth's `SecItemAdd` fails with
/// -34018 and *every* Auth call fails with `ERROR_KEYCHAIN_ERROR` (17995). Ad-hoc
/// signing is enough; no signing identity, team id or provisioning profile is needed,
/// which matters because none exist for this project (`MAN-003` is permanently `WAIVED`
/// per `DEC-004`). Nothing in the repository was changed to achieve this - the flags are
/// command-line only.
///
/// ## Credential hygiene
///
/// Both accounts are created at run time with randomly generated passwords that are
/// never logged, never persisted and never leave the process. Both accounts and all
/// their data are deleted before the harness exits.
enum FirebaseSmokeHarness {

    /// Launch argument that opts a Debug build into running the harness.
    static let launchArgument = "-FluxItFirebaseSmoke"

    static var isRequested: Bool {
        ProcessInfo.processInfo.arguments.contains(launchArgument)
    }

    private static let runId = String(UUID().uuidString.prefix(8)).lowercased()
    private static let opTimeout: UInt64 = 60

    private static var results: [String] = []

    /// Runs the matrix on a detached task and terminates the process with a non-zero
    /// status on any failure, so `xcrun simctl launch --console-pty` reports a result.
    static func runAndExit() {
        Task.detached(priority: .userInitiated) {
            var failure: Error?
            do {
                try await run()
            } catch {
                failure = error
            }
            await cleanUp()
            print("===== FB-008 IOS SMOKE MATRIX (run \(runId)) =====")
            results.forEach { print($0) }
            if let failure {
                print("RESULT: FAIL - \(failure)")
                print("===== end matrix =====")
                exit(1)
            }
            print("RESULT: PASS")
            print("===== end matrix =====")
            exit(0)
        }
    }

    // MARK: - Matrix

    private static var emailA = ""
    private static var emailB = ""
    private static var passwordA = ""
    private static var passwordB = ""
    private static var uidA = ""
    private static var uidB = ""

    private static func run() async throws {
        emailA = "fluxit-smoke-a-\(runId)@example.com"
        emailB = "fluxit-smoke-b-\(runId)@example.com"
        passwordA = randomPassword()
        passwordB = randomPassword()

        // --- 0. Runtime initialization ---------------------------------------
        guard let app = FirebaseApp.app() else {
            throw SmokeError("default FirebaseApp is nil; FirebaseBootstrap.start() did not run")
        }
        guard let projectId = app.options.projectID, !projectId.isEmpty else {
            throw SmokeError("resolved projectID is empty")
        }
        record("init", "PASS", "default FirebaseApp present at runtime (FirebaseBootstrap ran)")

        // --- 1/2. Two throwaway accounts -------------------------------------
        uidA = try await Auth.auth().createUser(withEmail: emailA, password: passwordA).user.uid
        record("auth-uid-A", "PASS", "sign-up returned a non-blank uid")
        uidB = try await Auth.auth().createUser(withEmail: emailB, password: passwordB).user.uid
        record("auth-uid-B", "PASS", "second sign-up returned a distinct non-blank uid")
        guard !uidA.isEmpty, !uidB.isEmpty, uidA != uidB else {
            throw SmokeError("expected two distinct non-empty uids")
        }

        // --- 3. LIVE cross-user denial preflight, before any real write ------
        // createUser leaves B as the current user.
        guard Auth.auth().currentUser?.uid == uidB else {
            throw SmokeError("expected to be signed in as the second account")
        }
        let victimDoc = listDoc(uid: uidA)
        let victimPhoto = photoRef(uid: uidA)

        try await expectFirestoreDenied("preflight-firestore-read") {
            _ = try await victimDoc.getDocument(source: .server)
        }
        try await expectFirestoreDenied("preflight-firestore-write") {
            try await victimDoc.setData(["intruder": true])
        }
        try await expectStorageDenied("preflight-storage-read") {
            _ = try await victimPhoto.data(maxSize: 1_000_000)
        }
        try await expectStorageDenied("preflight-storage-write") {
            _ = try await victimPhoto.putDataAsync(smallPng())
        }

        // --- 4. Sign in as the owner -----------------------------------------
        try await Auth.auth().signIn(withEmail: emailA, password: passwordA)
        let doc = listDoc(uid: uidA)
        let photo = photoRef(uid: uidA)

        // --- 5. Firestore write + listen -------------------------------------
        let onlineToken = "online-\(runId)"
        try await withSnapshotListener(doc) { register in
            try await doc.setData(["marker": onlineToken, "createdBy": "FB-008-ios"])
            try await register.wait(reason: "server-acknowledged snapshot") { snapshot in
                snapshot.get("marker") as? String == onlineToken && !snapshot.metadata.hasPendingWrites
            }
        }
        record("firestore-write-listen", "PASS", "server-acknowledged snapshot observed by the listener")

        // --- 6. Offline write, observed after reconnect ----------------------
        let offlineToken = "offline-\(runId)"
        try await withSnapshotListener(doc) { register in
            try await Firestore.firestore().disableNetwork()
            // Deliberately not awaited: while the network is disabled Firestore does not
            // call this completion until the server acknowledges the write. The local
            // effect is observed through the snapshot listener instead.
            let acked = AckBox()
            doc.setData(["offlineMarker": offlineToken], merge: true) { error in
                Task { await acked.complete(error) }
            }
            try await register.wait(reason: "local snapshot with pending write") { snapshot in
                snapshot.get("offlineMarker") as? String == offlineToken && snapshot.metadata.hasPendingWrites
            }
            if await acked.isComplete {
                throw SmokeError("write must still be unacknowledged while offline")
            }
            record("firestore-offline-write", "PASS", "local snapshot with hasPendingWrites=true while offline")

            try await Firestore.firestore().enableNetwork()
            try await register.wait(reason: "server-acknowledged snapshot after reconnect") { snapshot in
                snapshot.get("offlineMarker") as? String == offlineToken
                    && !snapshot.metadata.hasPendingWrites
                    && !snapshot.metadata.isFromCache
            }
            try await acked.awaitCompletion(timeoutSeconds: opTimeout)
        }
        record("firestore-reconnect", "PASS", "same write observed server-acknowledged after reconnect")

        // --- 7. Storage upload + download ------------------------------------
        let png = smallPng()
        _ = try await photo.putDataAsync(png)
        record("storage-upload", "PASS", "\(png.count)-byte PNG uploaded to the owner path")
        let downloaded = try await photo.data(maxSize: 1_000_000)
        guard downloaded == png else {
            throw SmokeError("downloaded bytes differ from the uploaded bytes")
        }
        record("storage-download", "PASS", "downloaded bytes byte-identical to the upload")

        // --- 8. Denial sweep against resources that actually exist -----------
        try await Auth.auth().signIn(withEmail: emailB, password: passwordB)
        try await expectFirestoreDenied("live-denied-read-existing-doc") {
            _ = try await doc.getDocument(source: .server)
        }
        try await expectStorageDenied("live-denied-read-existing-object") {
            _ = try await photo.data(maxSize: 1_000_000)
        }

        // --- 9. Storage delete, verified -------------------------------------
        try await Auth.auth().signIn(withEmail: emailA, password: passwordA)
        try await photo.delete()
        do {
            _ = try await photo.data(maxSize: 1_000_000)
            throw SmokeError("owner download succeeded after delete")
        } catch let error as NSError {
            guard StorageErrorCode(rawValue: error.code) == .objectNotFound else {
                throw SmokeError("after delete expected objectNotFound, got \(error)")
            }
        }
        record("storage-delete", "PASS", "owner download after delete fails with objectNotFound")
    }

    // MARK: - Denial assertions

    private static func expectFirestoreDenied(
        _ step: String,
        _ block: () async throws -> Void
    ) async throws {
        do {
            try await block()
        } catch let error as NSError {
            guard error.domain == FirestoreErrorDomain,
                  error.code == FirestoreErrorCode.permissionDenied.rawValue else {
                throw SmokeError("\(step): expected permissionDenied, got \(error)")
            }
            record(step, "DENIED (expected)", "FirestoreErrorCode.permissionDenied")
            return
        }
        throw SmokeError("\(step): expected permissionDenied but the operation succeeded")
    }

    private static func expectStorageDenied(
        _ step: String,
        _ block: () async throws -> Void
    ) async throws {
        do {
            try await block()
        } catch let error as NSError {
            // `objectNotFound` would mean the Rules ALLOWED the access and the object
            // simply was not there - exactly the false pass this preflight exists to
            // rule out - so it is rejected explicitly rather than accepted as a denial.
            guard StorageErrorCode(rawValue: error.code) == .unauthorized else {
                throw SmokeError("\(step): expected StorageErrorCode.unauthorized, got \(error)")
            }
            record(step, "DENIED (expected)", "StorageErrorCode.unauthorized")
            return
        }
        throw SmokeError("\(step): expected unauthorized but the operation succeeded")
    }

    // MARK: - Paths (must match firestore.rules / storage.rules exactly)

    /// `users/{uid}/lists/{listId}`
    private static func listDoc(uid: String) -> DocumentReference {
        Firestore.firestore()
            .collection("users").document(uid)
            .collection("lists").document("smoke-\(runId)")
    }

    /// `users/{uid}/items/{itemId}/{photoId}`
    private static func photoRef(uid: String) -> StorageReference {
        Storage.storage().reference()
            .child("users").child(uid)
            .child("items").child("smoke-\(runId)")
            .child("photo.png")
    }

    // MARK: - Support

    private struct SmokeError: Error, CustomStringConvertible {
        let description: String
        init(_ description: String) { self.description = description }
    }

    /// Collects snapshots from one listener so a test step can wait for a predicate.
    private actor SnapshotRegister {
        private var snapshots: [DocumentSnapshot] = []
        private var matched: Set<String> = []

        func append(_ snapshot: DocumentSnapshot) { snapshots.append(snapshot) }

        /// Polls the collected snapshots until `predicate` matches one of them.
        /// Each `reason` is satisfied at most once, so a later step cannot be
        /// satisfied by a snapshot an earlier step already consumed.
        func wait(
            reason: String,
            timeoutSeconds: UInt64 = 60,
            predicate: @Sendable (DocumentSnapshot) -> Bool
        ) async throws {
            let deadline = Date().addingTimeInterval(TimeInterval(timeoutSeconds))
            while Date() < deadline {
                if let index = snapshots.firstIndex(where: predicate) {
                    snapshots.removeSubrange(...index)
                    matched.insert(reason)
                    return
                }
                try await Task.sleep(nanoseconds: 100_000_000)
            }
            throw SmokeError("timed out waiting for \(reason)")
        }
    }

    private actor AckBox {
        private var completed = false
        private var error: Error?

        var isComplete: Bool { completed }

        func complete(_ error: Error?) {
            completed = true
            self.error = error
        }

        func awaitCompletion(timeoutSeconds: UInt64) async throws {
            let deadline = Date().addingTimeInterval(TimeInterval(timeoutSeconds))
            while Date() < deadline {
                if completed {
                    if let error { throw error }
                    return
                }
                try await Task.sleep(nanoseconds: 100_000_000)
            }
            throw SmokeError("timed out waiting for the offline write to be acknowledged")
        }
    }

    /// Attaches a metadata-including snapshot listener for the duration of `body` and
    /// always removes it afterwards, so no listener outlives its step.
    private static func withSnapshotListener(
        _ document: DocumentReference,
        _ body: (SnapshotRegister) async throws -> Void
    ) async throws {
        let register = SnapshotRegister()
        let registration = document.addSnapshotListener(includeMetadataChanges: true) { snapshot, _ in
            guard let snapshot else { return }
            Task { await register.append(snapshot) }
        }
        defer { registration.remove() }
        try await body(register)
    }

    private static func record(_ step: String, _ outcome: String, _ detail: String) {
        let paddedStep = step.padding(toLength: max(36, step.count), withPad: " ", startingAt: 0)
        let paddedOutcome = outcome.padding(toLength: max(18, outcome.count), withPad: " ", startingAt: 0)
        results.append("\(paddedStep) \(paddedOutcome) \(detail)")
    }

    private static func randomPassword() -> String {
        let alphabet = Array("abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#$%")
        var bytes = [UInt8](repeating: 0, count: 24)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return String(bytes.map { alphabet[Int($0) % alphabet.count] })
    }

    private static func smallPng() -> Data {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8))
        let image = renderer.image { context in
            UIColor(red: 0.18, green: 0.49, blue: 0.20, alpha: 1).setFill()
            context.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
        }
        return image.pngData() ?? Data()
    }

    /// Best-effort teardown: removes the harness's own document, object and both
    /// throwaway accounts. Failures are reported but never mask a real result.
    private static func cleanUp() async {
        try? await Firestore.firestore().enableNetwork()
        for (label, email, password) in [("A", emailA, passwordA), ("B", emailB, passwordB)] {
            guard !email.isEmpty else { continue }
            do {
                try await Auth.auth().signIn(withEmail: email, password: password)
                guard let user = Auth.auth().currentUser else { continue }
                try? await photoRef(uid: user.uid).delete()
                try? await listDoc(uid: user.uid).delete()
                try await user.delete()
                record("cleanup-account-\(label)", "PASS", "account and its data removed")
            } catch {
                record("cleanup-account-\(label)", "WARN", "teardown failed: \(error)")
            }
        }
        try? Auth.auth().signOut()
    }
}

#endif
