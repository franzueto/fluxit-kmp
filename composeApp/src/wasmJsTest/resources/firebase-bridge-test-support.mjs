// Test-only helpers for WebFirestoreCodecTest: builds real Firestore values in JS, runs
// them through the production bridge's conversions and describes the result as JSON.

import { Timestamp, increment, serverTimestamp } from "firebase/firestore";
import { firestoreDataToWire, wireToFirestoreData } from "./firebase-bridge.mjs";

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
