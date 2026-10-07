import ComposeApp
import FirebaseAuth
import Foundation

/// Swift implementation of the Kotlin-declared `IosAuthBridge` protocol.
///
/// The `FirebaseAuth` SPM target is not cinterop-reachable from `iosMain`, so
/// this file - not Kotlin - is where every Firebase Auth call on iOS lives. The Kotlin
/// side (`IosAuthRepository`) owns the session state machine, the listener lifecycle and
/// the error taxonomy; this file owns nothing but translation. Keeping it that thin is
/// deliberate: it is the part that cannot be unit-tested from Gradle, so there should be
/// as little logic in it as possible.
///
/// Nothing Firebase-specific leaves this file. Users are handed over as the shared
/// `AuthUser` value and failures as a plain `NSError` - Kotlin declares the completion
/// handlers as taking `NSError?` and Swift imports that as `(any Error)?`, so the SDK's
/// own error object crosses unchanged and only Kotlin's `mapAuthFailure` is allowed to
/// interpret it.
final class FirebaseAuthBridge: NSObject, IosAuthBridge {

    /// Resolved per call rather than stored, so merely registering the bridge at launch
    /// does not instantiate `Auth` for the default app. This mirrors Android, where
    /// `FirebaseAuth.getInstance()` is first touched when Koin resolves the adapter.
    private let authProvider: () -> Auth

    private var auth: Auth { authProvider() }

    init(authProvider: @escaping () -> Auth = { Auth.auth() }) {
        self.authProvider = authProvider
        super.init()
    }

    func currentUser() -> AuthUser? {
        auth.currentUser.map(FirebaseAuthBridge.toAuthUser)
    }

    func addAuthStateListener(listener: @escaping (AuthUser?) -> Void) -> any IosAuthListenerHandle {
        let handle = auth.addStateDidChangeListener { _, user in
            listener(user.map(FirebaseAuthBridge.toAuthUser))
        }
        return FirebaseAuthListenerHandle(auth: auth, handle: handle)
    }

    func reloadCurrentUser(completion: @escaping ((any Error)?) -> Void) {
        guard let user = auth.currentUser else {
            // No persisted credential: Kotlin already handles that case before calling
            // this, so reaching here means the user disappeared in between. Reporting
            // success lets Kotlin re-read `currentUser()` and resolve SignedOut.
            completion(nil)
            return
        }
        user.reload { error in completion(error) }
    }

    func signUp(email: String, password: String, completion: @escaping ((any Error)?) -> Void) {
        auth.createUser(withEmail: email, password: password) { _, error in
            completion(error)
        }
    }

    func signIn(email: String, password: String, completion: @escaping ((any Error)?) -> Void) {
        auth.signIn(withEmail: email, password: password) { _, error in
            completion(error)
        }
    }

    func sendPasswordResetEmail(email: String, completion: @escaping ((any Error)?) -> Void) {
        auth.sendPasswordReset(withEmail: email) { error in
            completion(error)
        }
    }

    /// Auth's share of the sign-out cache-clearing policy. Firestore/Storage cache clearing is deliberately
    /// not done here; that ordering belongs to `SessionAuthRepository` and the platform session cleanup.
    func signOut(completion: @escaping ((any Error)?) -> Void) {
        do {
            try auth.signOut()
            completion(nil)
        } catch {
            completion(error)
        }
    }

    private static func toAuthUser(_ user: User) -> AuthUser {
        AuthUser(uid: user.uid, email: user.email, isEmailVerified: user.isEmailVerified)
    }
}

/// Releases one Firebase auth-state listener.
///
/// This is what makes `AuthRepository.session`'s "cancelling the collector releases the
/// underlying listener" clause real on iOS: Kotlin's `awaitClose` calls
/// `remove()`. It is idempotent so a double cancellation cannot remove a *later*
/// listener registered by a different collector.
final class FirebaseAuthListenerHandle: NSObject, IosAuthListenerHandle {

    private let auth: Auth
    private var handle: AuthStateDidChangeListenerHandle?

    init(auth: Auth, handle: AuthStateDidChangeListenerHandle) {
        self.auth = auth
        self.handle = handle
        super.init()
    }

    func remove() {
        guard let handle = handle else { return }
        self.handle = nil
        auth.removeStateDidChangeListener(handle)
    }
}
