// Execute the Android/Swift query shapes through the JS client SDK.
// This checks server results and owner Rules, not native SDK integration or
// production index enforcement. See README's query inventory.
import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { assertFails } from '@firebase/rules-unit-testing';
import { collection, doc, getDocsFromServer, limit, onSnapshot, query, Timestamp, where, writeBatch } from 'firebase/firestore';
import { createTestEnvironment, ALICE, BOB } from './helpers.js';

const listPath = `users/${ALICE}/lists/active`;
const deletedListPath = `users/${ALICE}/lists/deleted`;
const itemPath = `${listPath}/items`;
const time = Timestamp.fromMillis(1_000);
let testEnv;
let alice;

function list(deletedAt = null) {
  return { name: 'synthetic', icon: 'CART', color: 'ORANGE', createdAt: time,
    updatedAt: time, deletedAt, totalItems: 402, completedItems: 401, schemaVersion: 1 };
}

function item(isCompleted = true, deletedAt = null) {
  return { listId: 'active', title: 'synthetic', description: null, isCompleted,
    photoRef: null, createdAt: time, updatedAt: time, deletedAt, schemaVersion: 1 };
}

before(async () => {
  testEnv = await createTestEnvironment();
  await testEnv.clearFirestore();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    const batch = writeBatch(db);
    batch.set(doc(db, listPath), list());
    batch.set(doc(db, deletedListPath), list(time));
    for (let i = 0; i < 401; i++) batch.set(doc(db, `${itemPath}/completed-${String(i).padStart(3, '0')}`), item());
    batch.set(doc(db, `${itemPath}/incomplete`), item(false));
    batch.set(doc(db, `${itemPath}/deleted`), item(true, time));
    // Legacy malformed document: missing field must not match deletedAt == null.
    const missing = item();
    delete missing.deletedAt;
    batch.set(doc(db, `${itemPath}/missing-deletedAt`), missing);
    batch.set(doc(db, `users/${BOB}/lists/foreign`), list());
    batch.set(doc(db, `users/${BOB}/lists/foreign/items/foreign`), { ...item(), listId: 'foreign' });
    await batch.commit();
  });
  alice = testEnv.authenticatedContext(ALICE).firestore();
});

after(async () => { await testEnv?.cleanup(); });

function serverSnapshot(reference) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { unsubscribe(); reject(new Error('Server snapshot timed out')); }, 10_000);
    const unsubscribe = onSnapshot(reference, { includeMetadataChanges: true }, (snapshot) => {
      if (snapshot.metadata.fromCache) return;
      clearTimeout(timer);
      unsubscribe();
      resolve(snapshot);
    }, (error) => { clearTimeout(timer); unsubscribe(); reject(error); });
  });
}

test('owner collection/document listeners return active and deleted raw documents for client mapping', async () => {
  const lists = await serverSnapshot(collection(alice, `users/${ALICE}/lists`));
  assert.deepEqual(lists.docs.map((entry) => entry.id), ['active', 'deleted']);
  assert.equal(lists.docs.find((entry) => entry.id === 'deleted').data().deletedAt.toMillis(), 1_000);
  const items = await serverSnapshot(collection(alice, itemPath));
  assert.equal(items.size, 404);
  assert.ok(items.docs.some((entry) => entry.id === 'deleted'));
  assert.equal((await serverSnapshot(doc(alice, `${itemPath}/incomplete`))).data().isCompleted, false);
  assert.equal((await serverSnapshot(doc(alice, deletedListPath))).exists(), true);
  await assertFails(getDocsFromServer(collection(alice, `users/${BOB}/lists`)));
  await assertFails(getDocsFromServer(collection(alice, `users/${BOB}/lists/foreign/items`)));
});

test('clear-completed equality query returns a bounded page then the remaining active completed item', async () => {
  // Both native adapters use the same two equalities with a default chunk of 400.
  const completed = query(collection(alice, itemPath), where('isCompleted', '==', true),
    where('deletedAt', '==', null), limit(400));
  const first = await getDocsFromServer(completed);
  assert.equal(first.size, 400);
  assert.ok(first.docs.every((entry) => entry.id.startsWith('completed-')));
  // Simulate a processed page with test-only Rules bypass; this test concerns
  // query selection. Real counter/tombstone writes are covered by Rules/native tests.
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    const batch = writeBatch(db);
    first.docs.forEach((entry) => batch.update(doc(db, entry.ref.path), { deletedAt: time }));
    await batch.commit();
  });
  const remaining = await getDocsFromServer(completed);
  assert.equal(remaining.size, 1);
  assert.ok(remaining.docs[0].id.startsWith('completed-'));
  assert.equal(first.docs.some((entry) => entry.id === remaining.docs[0].id), false);
});
