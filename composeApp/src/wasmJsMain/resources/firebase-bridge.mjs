// The web counterpart of the iOS Swift bridges: every Firebase JS SDK call lives here.
//
// Kotlin (wasmJsMain/.../firebase/WebFirebaseExternals.kt) imports this module; the Kotlin
// side owns the session state machine, listener lifecycle and error taxonomy, this file
// owns nothing but translation. Nothing Firebase-specific leaves it: users cross as
// { uid, email, emailVerified }, failures as { code, message }, and callbacks are invoked
// exactly once. Importing it has no side effects; Firebase starts in initializeFirebase().
//
// Copied next to the compiled Kotlin by the resources sync, so webpack bundles it together
// with the `firebase` npm dependency declared in composeApp/build.gradle.kts.

import { initializeApp } from "firebase/app";
import {
    browserLocalPersistence,
    connectAuthEmulator,
    createUserWithEmailAndPassword,
    indexedDBLocalPersistence,
    initializeAuth,
    onAuthStateChanged,
    reload,
    sendPasswordResetEmail,
    signInWithEmailAndPassword,
    signOut,
} from "firebase/auth";

let auth = null;

/** Idempotent. `emulatorHost` is null unless the build enables the local emulators. */
export function initializeFirebase(apiKey, authDomain, projectId, storageBucket, messagingSenderId, appId,
                                   emulatorHost, authPort) {
    if (auth !== null) return;
    const app = initializeApp({ apiKey, authDomain, projectId, storageBucket, messagingSenderId, appId });
    // Persisted credential (IndexedDB, localStorage fallback) is what restores the session on
    // reload. No popup/redirect resolver: email + password only, and a smaller bundle.
    auth = initializeAuth(app, { persistence: [indexedDBLocalPersistence, browserLocalPersistence] });
    if (emulatorHost !== null) {
        connectAuthEmulator(auth, `http://${emulatorHost}:${authPort}`, { disableWarnings: true });
    }
}

function requireAuth() {
    if (auth === null) throw new Error("initializeFirebase() has not run");
    return auth;
}

function toUser(user) {
    return user === null ? null : { uid: user.uid, email: user.email, emailVerified: user.emailVerified };
}

function toError(error) {
    return { code: String(error?.code ?? "unknown"), message: String(error?.message ?? "") };
}

/** Settles `promise` into `done(null)` or `done({ code, message })`, exactly once. */
function complete(promise, done) {
    promise.then(() => done(null), (error) => done(toError(error)));
}

export function authCurrentUser() {
    return toUser(requireAuth().currentUser);
}

/** Calls `done()` once the persisted credential has been loaded (currentUser is meaningful). */
export function authStateReady(done) {
    requireAuth().authStateReady().then(() => done(), () => done());
}

/** Returns an opaque handle for authRemoveStateListener. The SDK reports the current state first. */
export function authAddStateListener(listener) {
    return { unsubscribe: onAuthStateChanged(requireAuth(), (user) => listener(toUser(user))) };
}

/** Idempotent. */
export function authRemoveStateListener(handle) {
    const unsubscribe = handle.unsubscribe;
    handle.unsubscribe = null;
    if (unsubscribe) unsubscribe();
}

/** Re-reads the current user from the server; success when nobody is signed in. */
export function authReloadCurrentUser(done) {
    const user = requireAuth().currentUser;
    if (user === null) {
        done(null);
        return;
    }
    complete(reload(user), done);
}

export function authSignUp(email, password, done) {
    complete(createUserWithEmailAndPassword(requireAuth(), email, password), done);
}

export function authSignIn(email, password, done) {
    complete(signInWithEmailAndPassword(requireAuth(), email, password), done);
}

export function authSendPasswordResetEmail(email, done) {
    complete(sendPasswordResetEmail(requireAuth(), email), done);
}

/** Auth's share of sign-out; data clients are cleared by clearSessionData(). */
export function authSignOut(done) {
    complete(signOut(requireAuth()), done);
}

/**
 * Local data cleanup between sessions (SessionCleanup.clear on web). Phase 2 has no data
 * clients yet, so there is nothing to clear. Phase 3 terminates and recreates the
 * in-memory Firestore instance (decision D4); Phase 4 cancels in-flight Storage tasks.
 */
export function clearSessionData(done) {
    done(null);
}
