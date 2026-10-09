// The web counterpart of the iOS Swift bridges: every Firebase JS SDK call lives here.
//
// Kotlin (wasmJsMain/.../firebase/WebFirebaseExternals.kt) imports this module; the Kotlin
// side owns the session state machine, listener lifecycle and error taxonomy, this file
// owns nothing but translation. Nothing Firebase-specific leaves it: users cross as
// { uid, email, emailVerified }, failures as { code, message }, and callbacks are invoked
// exactly once. Importing it has no side effects; Firebase starts in initializeFirebase().
//
// Firestore values cross as "wire" entries { key, type, text, bool, number }, where type is
// null | text | bool | number | timestamp (number = epoch millis) and, for writes only,
// serverTimestamp | increment. The Kotlin side is WebFirestoreCodec.kt.
//
// Storage objects are addressed by the Kotlin-built photoRef verbatim; bytes cross as
// Uint8Array (upload) and ArrayBuffer (download).
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
import {
    Timestamp,
    collection,
    connectFirestoreEmulator,
    doc,
    getDocFromServer,
    getDocs,
    increment,
    initializeFirestore,
    limit,
    memoryLocalCache,
    onSnapshot,
    query,
    runTransaction,
    serverTimestamp,
    setDoc,
    terminate,
    updateDoc,
    where,
    writeBatch,
} from "firebase/firestore";
import {
    connectStorageEmulator,
    deleteObject,
    getBytes,
    getStorage,
    ref,
    uploadBytesResumable,
} from "firebase/storage";

let app = null;
let auth = null;
let emulator = null;

/** Idempotent. `emulatorHost` is null unless the build enables the local emulators. */
export function initializeFirebase(apiKey, authDomain, projectId, storageBucket, messagingSenderId, appId,
                                   emulatorHost, authPort, firestorePort, storagePort) {
    if (auth !== null) return;
    app = initializeApp({ apiKey, authDomain, projectId, storageBucket, messagingSenderId, appId });
    // Persisted credential (IndexedDB, localStorage fallback) is what restores the session on
    // reload. No popup/redirect resolver: email + password only, and a smaller bundle.
    auth = initializeAuth(app, { persistence: [indexedDBLocalPersistence, browserLocalPersistence] });
    if (emulatorHost !== null) {
        emulator = { host: emulatorHost, firestorePort, storagePort };
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

// --- Firestore ---------------------------------------------------------------------------

let db = null;
let stopped = [];
let cleanup = null;

/**
 * The Firestore instance, created on first use. In-memory cache only (decision D4): nothing
 * survives a reload, so sign-out cleanup is terminate-and-recreate, never a disk wipe.
 */
function firestore() {
    if (app === null) throw new Error("initializeFirebase() has not run");
    if (db === null) {
        db = initializeFirestore(app, { localCache: memoryLocalCache() });
        if (emulator !== null) connectFirestoreEmulator(db, emulator.host, emulator.firestorePort);
    }
    return db;
}

/** A path segment from Kotlin; rejects anything that would address a different depth. */
function segment(value) {
    if (typeof value !== "string" || value.length === 0 || value.includes("/")) {
        throw Object.assign(new Error("invalid path segment"), { code: "invalid-argument" });
    }
    return value;
}

function listsCollection(uid) {
    return collection(firestore(), "users", segment(uid), "lists");
}

function listDoc(uid, listId) {
    return doc(listsCollection(uid), segment(listId));
}

function itemsCollection(uid, listId) {
    return collection(listDoc(uid, listId), "items");
}

function itemDoc(uid, listId, itemId) {
    return doc(itemsCollection(uid, listId), segment(itemId));
}

/** Wire entries → Firestore data. */
export function wireToFirestoreData(wire) {
    const data = {};
    for (const field of wire) {
        switch (field.type) {
            case "null": data[field.key] = null; break;
            case "text": data[field.key] = field.text; break;
            case "bool": data[field.key] = field.bool; break;
            case "number": data[field.key] = field.number; break;
            case "timestamp": data[field.key] = Timestamp.fromMillis(field.number); break;
            case "serverTimestamp": data[field.key] = serverTimestamp(); break;
            case "increment": data[field.key] = increment(field.number); break;
            default: throw Object.assign(new Error("unknown wire type"), { code: "invalid-argument" });
        }
    }
    return data;
}

/**
 * Firestore data → wire entries. Same decoding as the iOS bridge: numbers are truncated to
 * integers, and types FluxIt does not store (maps, arrays, bytes, geo points, references)
 * are left out, so the shared mapper sees them as missing.
 */
export function firestoreDataToWire(data) {
    const wire = [];
    for (const [key, value] of Object.entries(data ?? {})) {
        if (value === null) wire.push({ key, type: "null", text: null, bool: false, number: 0 });
        else if (typeof value === "string") wire.push({ key, type: "text", text: value, bool: false, number: 0 });
        else if (typeof value === "boolean") wire.push({ key, type: "bool", text: null, bool: value, number: 0 });
        else if (typeof value === "number") wire.push({ key, type: "number", text: null, bool: false, number: Math.trunc(value) });
        else if (value instanceof Timestamp) wire.push({ key, type: "timestamp", text: null, bool: false, number: value.toMillis() });
    }
    return wire;
}

/** Local server-timestamp estimates keep offline creations visible, as on iOS. */
function toDocument(snapshot) {
    return { id: snapshot.id, fields: firestoreDataToWire(snapshot.data({ serverTimestamps: "estimate" })) };
}

function listen(target, includeMetadataChanges, next, onError) {
    let unsubscribe;
    try {
        unsubscribe = onSnapshot(target, { includeMetadataChanges }, next, (error) => onError(toError(error)));
    } catch (error) {
        onError(toError(error));
        unsubscribe = () => {};
    }
    return { unsubscribe };
}

/** Lists of a user (`listId` null) or items of a list. Reports documents plus snapshot metadata. */
export function fsObserveCollection(uid, listId, includeMetadataChanges, onSnapshotDocs, onError) {
    const target = () => (listId === null ? listsCollection(uid) : itemsCollection(uid, listId));
    let resolved;
    try { resolved = target(); } catch (error) { onError(toError(error)); return { unsubscribe: null }; }
    return listen(resolved, includeMetadataChanges, (snapshot) => onSnapshotDocs(
        snapshot.docs.map(toDocument), snapshot.metadata.fromCache, snapshot.metadata.hasPendingWrites,
    ), onError);
}

/** A list (`itemId` null) or an item document; reports null when it does not exist. */
export function fsObserveDocument(uid, listId, itemId, onSnapshotDoc, onError) {
    let resolved;
    try { resolved = itemId === null ? listDoc(uid, listId) : itemDoc(uid, listId, itemId); }
    catch (error) { onError(toError(error)); return { unsubscribe: null }; }
    return listen(resolved, false, (snapshot) => onSnapshotDoc(snapshot.exists() ? toDocument(snapshot) : null), onError);
}

/** Idempotent. */
export function fsRemoveListener(handle) {
    authRemoveStateListener(handle);
}

/** Settles `run()` (sync throw or promise) into `done(value, null)` or `done(fallback, error)`. */
function settle(run, done, fallback) {
    let promise;
    try { promise = Promise.resolve(run()); } catch (error) { promise = Promise.reject(error); }
    promise.then((value) => done(value, null), (error) => done(fallback, toError(error)));
}

/** New auto-ID list, whole-document write. */
export function fsCreateList(uid, wire, done) {
    settle(async () => {
        const reference = doc(listsCollection(uid));
        await setDoc(reference, wireToFirestoreData(wire));
        return reference.id;
    }, done, null);
}

/** Field-scoped update of a list (`itemId` null) or an item. */
export function fsUpdateFields(uid, listId, itemId, wire, done) {
    settle(() => updateDoc(itemId === null ? listDoc(uid, listId) : itemDoc(uid, listId, itemId), wireToFirestoreData(wire)),
        (_, error) => done(error), null);
}

/** One batch: new auto-ID item plus `totalItems` +1 on its list. */
export function fsAddItem(uid, listId, wire, done) {
    settle(async () => {
        const itemRef = doc(itemsCollection(uid, listId));
        const batch = writeBatch(firestore());
        batch.set(itemRef, wireToFirestoreData(wire));
        batch.update(listDoc(uid, listId), { totalItems: increment(1) });
        await batch.commit();
        return itemRef.id;
    }, done, null);
}

/**
 * Transaction: read the item, ask Kotlin's `decide(fieldsWire | null)` for
 * { kind: "noop" | "patch" | "delete", fields, delta } and apply it. Same retry rule as the
 * iOS bridge: a permission-denied after a read is retried (up to 3 times) only if a server
 * re-read shows the item changed, since a stale counter delta can fail the Rules.
 */
export function fsMutateItemWithCounters(uid, listId, itemId, decide, done) {
    settle(async () => {
        const itemRef = itemDoc(uid, listId, itemId);
        const listRef = listDoc(uid, listId);
        for (let retriesRemaining = 3; ; retriesRemaining--) {
            let read = null;
            try {
                await runTransaction(firestore(), async (transaction) => {
                    const snapshot = await transaction.get(itemRef);
                    read = readCounterState(snapshot);
                    const outcome = decide(snapshot.exists() ? firestoreDataToWire(snapshot.data()) : null);
                    if (outcome.kind === "patch") {
                        transaction.update(itemRef, wireToFirestoreData(outcome.fields));
                    } else if (outcome.kind === "delete") {
                        transaction.delete(itemRef);
                    }
                    if (outcome.kind !== "noop" && outcome.delta.length > 0) {
                        transaction.update(listRef, wireToFirestoreData(outcome.delta));
                    }
                });
                return;
            } catch (error) {
                if (error?.code !== "permission-denied" || read === null || retriesRemaining === 0) throw error;
                let current;
                try { current = readCounterState(await getDocFromServer(itemRef)); } catch { throw error; }
                if (sameCounterState(read, current)) throw error;
            }
        }
    }, (_, error) => done(error), null);
}

function readCounterState(snapshot) {
    const deletedAt = snapshot.get("deletedAt");
    return {
        exists: snapshot.exists(),
        completed: typeof snapshot.get("isCompleted") === "boolean" ? snapshot.get("isCompleted") : null,
        deletedAt: deletedAt instanceof Timestamp ? deletedAt : null,
    };
}

function sameCounterState(a, b) {
    const sameDeletedAt = a.deletedAt === null || b.deletedAt === null
        ? a.deletedAt === b.deletedAt
        : a.deletedAt.isEqual(b.deletedAt);
    return a.exists === b.exists && a.completed === b.completed && sameDeletedAt;
}

/**
 * One chunk of clear-completed: tombstone up to `chunkSize` active completed items with
 * `patchWire` and decrement both counters by the count, in one batch. Reports the count.
 */
export function fsClearCompletedChunk(uid, listId, chunkSize, patchWire, done) {
    settle(async () => {
        const snapshot = await getDocs(query(
            itemsCollection(uid, listId),
            where("isCompleted", "==", true),
            where("deletedAt", "==", null),
            limit(chunkSize),
        ));
        if (snapshot.empty) return 0;
        const count = snapshot.size;
        const patch = wireToFirestoreData(patchWire);
        const batch = writeBatch(firestore());
        for (const document of snapshot.docs) batch.update(document.ref, patch);
        batch.update(listDoc(uid, listId), { totalItems: increment(-count), completedItems: increment(-count) });
        await batch.commit();
        return count;
    }, done, 0);
}

// --- Storage -----------------------------------------------------------------------------

let storageService = null;
const storageTasks = new Set();

function storage() {
    if (app === null) throw new Error("initializeFirebase() has not run");
    if (storageService === null) {
        storageService = getStorage(app);
        if (emulator !== null) connectStorageEmulator(storageService, emulator.host, emulator.storagePort);
    }
    return storageService;
}

function canceledError() {
    return { code: "storage/canceled", message: "Storage task cancelled by session cleanup" };
}

/**
 * Runs a Storage operation as a session task that cancelStorageTasks() can stop, like the
 * iOS bridge's tracked tasks. `start()` returns { promise, cancel }; `cancel` is null when the
 * SDK cannot cancel the request (downloads), in which case the caller is answered with
 * storage/canceled at once and the late result is dropped. Settles `done` exactly once.
 * Exported for the bridge tests.
 */
export function storageTask(start, done, fallback) {
    let settled = false;
    let markFinished;
    const task = { cancel: null, finished: new Promise((resolve) => { markFinished = resolve; }) };
    const settle = (value, error) => {
        if (settled) return;
        settled = true;
        storageTasks.delete(task);
        markFinished();
        done(value, error);
    };
    storageTasks.add(task);
    let operation;
    try { operation = start(); } catch (error) { settle(fallback, toError(error)); return; }
    task.cancel = operation.cancel ?? (() => settle(fallback, canceledError()));
    operation.promise.then((value) => settle(value, null), (error) => settle(fallback, toError(error)));
}

/**
 * Cancels every running upload/download and waits until each has reported back. Exported for
 * the bridge tests. A snapshot is enough (unlike the Swift bridge's `cancelling` flag): the
 * shared SessionAuthRepository closes SessionWork before cleanup, so no new task can start.
 */
export function cancelStorageTasks() {
    const outgoing = [...storageTasks];
    for (const task of outgoing) task.cancel?.();
    return Promise.all(outgoing.map((task) => task.finished));
}

/** New object at `photoRef` with `mimeType` as its content type. */
export function storageUpload(photoRef, bytes, mimeType, done) {
    storageTask(() => {
        const upload = uploadBytesResumable(ref(storage(), photoRef), bytes, { contentType: mimeType });
        return { promise: upload.then(() => undefined), cancel: () => { upload.cancel(); } };
    }, (_, error) => done(error), null);
}

/**
 * The object's bytes, at most `maxSize`. The JS SDK silently truncates at its limit, so one
 * extra byte is requested and a larger object fails with storage/download-size-exceeded,
 * like the Android and iOS SDKs.
 */
export function storageDownload(photoRef, maxSize, done) {
    storageTask(() => ({
        promise: getBytes(ref(storage(), photoRef), maxSize + 1).then((buffer) => {
            if (buffer.byteLength > maxSize) {
                throw { code: "storage/download-size-exceeded", message: "Object is larger than the download limit" };
            }
            return buffer;
        }),
        cancel: null,
    }), done, null);
}

/** Deletes the object at `photoRef`. Not a session task, as on iOS. */
export function storageDelete(photoRef, done) {
    settle(() => deleteObject(ref(storage(), photoRef)), (_, error) => done(error), null);
}

/**
 * Local data cleanup between sessions (SessionCleanup.clear on web): cancel running Storage
 * uploads and downloads, then terminate the in-memory Firestore instance (dropping its cache,
 * listeners and queued writes) so the next use creates a fresh one. A failed termination
 * keeps the stopped instance for a retry. Storage keeps no local data.
 */
export function clearSessionData(done) {
    if (cleanup === null) {
        cleanup = (async () => {
            await cancelStorageTasks();
            // Includes an instance created after an earlier failed attempt.
            if (db !== null) {
                stopped.push(db);
                db = null;
            }
            await Promise.all(stopped.map((instance) => terminate(instance)));
            stopped = [];
        })().finally(() => { cleanup = null; });
    }
    cleanup.then(() => done(null), (error) => done(toError(error)));
}
