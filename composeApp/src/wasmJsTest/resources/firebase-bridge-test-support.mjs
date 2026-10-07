// Test-only helpers for WebFirestoreCodecTest (builds real Firestore values in JS, runs them
// through the production bridge's conversions and describes the result as JSON) and
// WebStorageSessionTasksTest.

import { Timestamp, increment, serverTimestamp } from "firebase/firestore";
import {
    cancelStorageTasks,
    clearSessionData,
    firestoreDataToWire,
    storageTask,
    wireToFirestoreData,
} from "./firebase-bridge.mjs";

/** Wire entries → Firestore data, described per key. */
export function describeFirestoreData(wire) {
    const described = {};
    for (const [key, value] of Object.entries(wireToFirestoreData(wire))) {
        if (value instanceof Timestamp) described[key] = `timestamp:${value.toMillis()}`;
        else if (value !== null && typeof value === "object" && value.isEqual(serverTimestamp())) described[key] = "serverTimestamp";
        else if (value !== null && typeof value === "object") {
            const match = [-2, -1, 1, 2].find((n) => value.isEqual(increment(n)));
            described[key] = match === undefined ? "unknown-sentinel" : `increment:${match}`;
        }
        else described[key] = value === null ? "null" : `${typeof value}:${value}`;
    }
    return JSON.stringify(described);
}

/** A document's data as Firestore returns it, including types FluxIt does not store. */
export function sampleSnapshotWire() {
    return firestoreDataToWire({
        name: "Groceries",
        isCompleted: true,
        totalItems: 3,
        fractional: 2.9,
        deletedAt: null,
        createdAt: Timestamp.fromMillis(1_700_000_000_123),
        nested: { a: 1 },
        tags: ["x"],
    });
}

// --- Storage session tasks (WebStorageSessionTasksTest) -----------------------------------

/**
 * Fake Storage operations through the bridge's session-task tracking: a cancellable upload,
 * a download the SDK cannot cancel, one already finished and one whose start throws. Cleanup
 * runs through `cancelStorageTasks()` or, with `viaClearSessionData`, the production
 * `clearSessionData()` (no Firestore instance exists in the test bundle). Reports what each
 * caller saw, in order, plus the cleanup result.
 */
export function describeStorageCancellation(viaClearSessionData, done) {
    const seen = [];
    const report = (name) => (value, error) => seen.push(`${name}:${error?.code ?? value}`);

    storageTask(() => { throw { code: "storage/invalid-argument", message: "" }; }, report("throws"), null);

    let rejectUpload;
    let uploadCancelled = 0;
    storageTask(() => ({
        promise: new Promise((_, reject) => { rejectUpload = reject; }),
        cancel: () => { uploadCancelled++; rejectUpload({ code: "storage/canceled", message: "" }); },
    }), report("upload"), null);

    let resolveDownload;
    storageTask(() => ({ promise: new Promise((resolve) => { resolveDownload = resolve; }), cancel: null }),
        report("download"), null);

    storageTask(() => ({ promise: Promise.resolve("ok"), cancel: () => seen.push("finished-task-cancelled") }),
        report("finished"), null);

    const cleanup = viaClearSessionData
        ? () => new Promise((resolve) => clearSessionData((error) => { seen.push(`cleanup:${error?.code ?? "ok"}`); resolve(); }))
        : () => cancelStorageTasks().then(() => { seen.push("cleanup:ok"); });

    Promise.resolve()
        .then(cleanup)
        .then(() => {
            resolveDownload("late"); // dropped: the caller already heard storage/canceled
            return cancelStorageTasks(); // nothing is left to cancel
        })
        .then(() => setTimeout(() => done(JSON.stringify({ seen, uploadCancelled })), 0));
}
