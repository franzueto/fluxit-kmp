// baseline Firestore Rules tests: owner-allowed, cross-user denied,
// unauthenticated denied, and unmatched paths denied (deny-by-default).
import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, deleteDoc, writeBatch, increment, serverTimestamp, Timestamp } from 'firebase/firestore';
import { createTestEnvironment, ALICE, BOB } from './helpers.js';

const ALICE_USER = `users/${ALICE}`;
const ALICE_LIST = `users/${ALICE}/lists/list1`;
const ALICE_ITEM = `users/${ALICE}/lists/list1/items/item1`;
const ALICE_CLEANUP_JOB = `users/${ALICE}/listCleanupJobs/seeded-claim`;
const ALICE_CLEANUP_PHOTO = `${ALICE_CLEANUP_JOB}/photos/item1`;
const BOB_USER = `users/${BOB}`;
const BOB_LIST = `users/${BOB}/lists/list1`;
const BOB_ITEM = `users/${BOB}/lists/list1/items/item1`;

function validList(name = 'groceries', time = serverTimestamp()) {
  return {
    name, icon: 'CART', color: 'ORANGE', createdAt: time, updatedAt: time,
    deletedAt: null, totalItems: 0, completedItems: 0, schemaVersion: 1,
  };
}

function validItem(listId = 'list1', title = 'milk', time = serverTimestamp()) {
  return {
    listId, title, description: null, isCompleted: false, photoRef: null,
    createdAt: time, updatedAt: time, deletedAt: null, schemaVersion: 1,
  };
}

let testEnv;
let alice;      // signed in as ALICE
let unauth;     // no auth token

before(async () => {
  testEnv = await createTestEnvironment();
  await testEnv.clearFirestore();

  // Seed Bob's and Alice's data with Rules bypassed, so every denial asserted
  // below is a genuine Rules denial against an EXISTING document rather than a
  // vacuous pass on a missing document.
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, BOB_USER), { displayName: 'bob' });
    const seededAt = Timestamp.now();
    await setDoc(doc(db, BOB_LIST), { ...validList("bob's list", seededAt), totalItems: 1 });
    await setDoc(doc(db, BOB_ITEM), validItem('list1', "bob's item", seededAt));
    await setDoc(doc(db, ALICE_USER), { displayName: 'alice' });
    await setDoc(doc(db, ALICE_LIST), { ...validList("alice's list", seededAt), totalItems: 1 });
    await setDoc(doc(db, ALICE_ITEM), validItem('list1', "alice's item", seededAt));
    await setDoc(doc(db, ALICE_CLEANUP_JOB), { claimedAt: 1 });
    await setDoc(doc(db, ALICE_CLEANUP_PHOTO), { photoRef: 'synthetic' });
  });

  alice = testEnv.authenticatedContext(ALICE).firestore();
  unauth = testEnv.unauthenticatedContext().firestore();
});

after(async () => {
  await testEnv?.cleanup();
});

// --- Owner is allowed ------------------------------------------------------

test('owner can read own user document', async () => {
  await assertSucceeds(getDoc(doc(alice, ALICE_USER)));
});

test('owner can write own user document', async () => {
  await assertSucceeds(setDoc(doc(alice, ALICE_USER), { displayName: 'alice2' }));
});

test('owner can read own list', async () => {
  await assertSucceeds(getDoc(doc(alice, ALICE_LIST)));
});

test('owner can write own list', async () => {
  await assertSucceeds(updateDoc(doc(alice, ALICE_LIST), { name: 'groceries', updatedAt: serverTimestamp() }));
});

test('owner can read own item', async () => {
  await assertSucceeds(getDoc(doc(alice, ALICE_ITEM)));
});

test('owner can write own item', async () => {
  await assertSucceeds(updateDoc(doc(alice, ALICE_ITEM), { title: 'milk', updatedAt: serverTimestamp() }));
});

test('owner can atomically add an item and update its existing list counter', async () => {
  const batch = writeBatch(alice);
  batch.set(doc(alice, `users/${ALICE}/lists/list1/items/new-batch-item`), validItem());
  batch.update(doc(alice, ALICE_LIST), { totalItems: increment(1) });
  await assertSucceeds(batch.commit());
});

test('owner can batch many item writes under the same existing list', async () => {
  const batch = writeBatch(alice);
  for (let i = 0; i < 30; i++) {
    batch.set(doc(alice, `users/${ALICE}/lists/list1/items/bulk-${i}`), validItem('list1', 'bulk'));
  }
  batch.update(doc(alice, ALICE_LIST), { totalItems: increment(30) });
  await assertSucceeds(batch.commit());
});

test('owner can commit a full 400-item client chunk with one parent counter update', async () => {
  const list = doc(alice, `users/${ALICE}/lists/full-chunk`);
  await assertSucceeds(setDoc(list, validList('Full chunk')));
  const batch = writeBatch(alice);
  for (let i = 0; i < 400; i++) {
    batch.set(doc(alice, `users/${ALICE}/lists/full-chunk/items/item-${i}`),
      validItem('full-chunk', 'bulk'));
  }
  batch.update(list, { totalItems: increment(400) });
  await assertSucceeds(batch.commit());
  assert.equal((await assertSucceeds(getDoc(list))).data().totalItems, 400);
});

test('owner delayed item write is DENIED after its list parent is gone', async () => {
  const list = doc(alice, `users/${ALICE}/lists/removed-parent`);
  const item = doc(alice, `users/${ALICE}/lists/removed-parent/items/offline-item`);
  await assertSucceeds(setDoc(list, validList('temporary')));
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await deleteDoc(doc(ctx.firestore(), `users/${ALICE}/lists/removed-parent`));
  });
  await assertFails(setDoc(item, validItem('removed-parent', 'queued offline item')));
});

test('owner cannot hard-delete a list and strand its item subcollection', async () => {
  const list = doc(alice, `users/${ALICE}/lists/list1`);
  await assertFails(deleteDoc(list));
  assert.equal((await assertSucceeds(getDoc(list))).exists(), true);
});

test('owner cannot batch-delete a list and create an orphan item', async () => {
  const list = doc(alice, `users/${ALICE}/lists/batch-parent`);
  const item = doc(alice, `users/${ALICE}/lists/batch-parent/items/orphan`);
  await assertSucceeds(setDoc(list, validList('temporary')));
  const batch = writeBatch(alice);
  batch.delete(list);
  batch.set(item, validItem('batch-parent', 'orphan'));
  await assertFails(batch.commit());
  assert.equal((await assertSucceeds(getDoc(list))).exists(), true);
});

// --- Schema, immutable ownership, and counter bounds -------------

test('owner can create a complete list, tombstone it, and restore it', async () => {
  const list = doc(alice, `users/${ALICE}/lists/lifecycle`);
  await assertSucceeds(setDoc(list, validList('Lifecycle')));
  await assertSucceeds(updateDoc(list, { deletedAt: serverTimestamp() }));
  await assertSucceeds(updateDoc(list, { deletedAt: null }));
  assert.equal((await assertSucceeds(getDoc(list))).data().deletedAt, null);
});

test('owner can atomically add, complete, tombstone, restore, and hard-delete an item with bounded counters', async () => {
  const list = doc(alice, `users/${ALICE}/lists/item-lifecycle`);
  const item = doc(alice, `users/${ALICE}/lists/item-lifecycle/items/item-a`);
  await assertSucceeds(setDoc(list, validList('Item lifecycle')));
  let batch = writeBatch(alice);
  batch.set(item, validItem('item-lifecycle'));
  batch.update(list, { totalItems: increment(1) });
  await assertSucceeds(batch.commit());
  batch = writeBatch(alice);
  batch.update(item, { isCompleted: true, updatedAt: serverTimestamp() });
  batch.update(list, { completedItems: increment(1) });
  await assertSucceeds(batch.commit());
  batch = writeBatch(alice);
  batch.update(item, { deletedAt: serverTimestamp(), updatedAt: serverTimestamp() });
  batch.update(list, { totalItems: increment(-1), completedItems: increment(-1) });
  await assertSucceeds(batch.commit());
  batch = writeBatch(alice);
  batch.update(item, { deletedAt: null, updatedAt: serverTimestamp() });
  batch.update(list, { totalItems: increment(1), completedItems: increment(1) });
  await assertSucceeds(batch.commit());
  batch = writeBatch(alice);
  batch.delete(item);
  batch.update(list, { totalItems: increment(-1), completedItems: increment(-1) });
  await assertSucceeds(batch.commit());
  const result = (await assertSucceeds(getDoc(list))).data();
  assert.equal(result.totalItems, 0);
  assert.equal(result.completedItems, 0);
});

test('owner can set only a same-owner, same-item photoRef', async () => {
  await assertSucceeds(updateDoc(doc(alice, ALICE_ITEM), {
    photoRef: `users/${ALICE}/items/item1/photo-1`, updatedAt: serverTimestamp(),
  }));
  await assertFails(updateDoc(doc(alice, ALICE_ITEM), {
    photoRef: `users/${BOB}/items/item1/photo-2`, updatedAt: serverTimestamp(),
  }));
  await assertFails(updateDoc(doc(alice, ALICE_ITEM), {
    photoRef: `users/${ALICE}/items/other-item/photo-2`, updatedAt: serverTimestamp(),
  }));
});

test('photoRef owner comparison is exact even when an auth UID contains regex punctuation', async () => {
  const specialUid = 'a.b';
  const special = testEnv.authenticatedContext(specialUid).firestore();
  const list = doc(special, `users/${specialUid}/lists/list1`);
  const item = doc(special, `users/${specialUid}/lists/list1/items/item1`);
  await assertSucceeds(setDoc(list, validList('Special UID')));
  const batch = writeBatch(special);
  batch.set(item, validItem());
  batch.update(list, { totalItems: increment(1) });
  await assertSucceeds(batch.commit());
  await assertFails(updateDoc(item, {
    photoRef: 'users/axb/items/item1/photo-1', updatedAt: serverTimestamp(),
  }));
  await assertSucceeds(updateDoc(item, {
    photoRef: 'users/a.b/items/item1/photo-1', updatedAt: serverTimestamp(),
  }));
});

test('owner cannot create sparse or extra-field documents', async () => {
  await assertFails(setDoc(doc(alice, `users/${ALICE}/lists/sparse`), { name: 'sparse' }));
  await assertFails(setDoc(doc(alice, `users/${ALICE}/lists/extra`), {
    ...validList(), ownerUid: ALICE,
  }));
  await assertFails(setDoc(doc(alice, `users/${ALICE}/lists/list1/items/extra`), {
    ...validItem(), ownerUid: ALICE,
  }));
});

test('owner cannot create invalid list types, enums, times, or counters', async () => {
  const path = `users/${ALICE}/lists/invalid-list`;
  await assertFails(setDoc(doc(alice, path), { ...validList(), icon: 'UNKNOWN' }));
  await assertFails(setDoc(doc(alice, path), { ...validList(), name: 42 }));
  await assertFails(setDoc(doc(alice, path), { ...validList(), name: '   ' }));
  await assertFails(setDoc(doc(alice, path), { ...validList(), createdAt: 'yesterday' }));
  await assertFails(setDoc(doc(alice, path), { ...validList(), totalItems: -1 }));
  await assertFails(setDoc(doc(alice, path), { ...validList(), totalItems: 1 }));
  await assertFails(setDoc(doc(alice, path), { ...validList(), completedItems: 1 }));
});

test('owner cannot create invalid item types or move an item to another list', async () => {
  const path = `users/${ALICE}/lists/list1/items/invalid-item`;
  await assertFails(setDoc(doc(alice, path), { ...validItem('other-list') }));
  await assertFails(setDoc(doc(alice, path), { ...validItem(), isCompleted: 'yes' }));
  await assertFails(setDoc(doc(alice, path), { ...validItem(), title: '   ' }));
  await assertFails(setDoc(doc(alice, path), { ...validItem(), isCompleted: true }));
  await assertFails(setDoc(doc(alice, path), { ...validItem(), photoRef: `users/${ALICE}/items/invalid-item/new` }));
  await assertFails(setDoc(doc(alice, path), { ...validItem(), description: 'x'.repeat(2001) }));
});

test('owner cannot change immutable list creation or schema fields', async () => {
  await assertFails(updateDoc(doc(alice, ALICE_LIST), { createdAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(alice, ALICE_LIST), { schemaVersion: 2 }));
  await assertFails(updateDoc(doc(alice, ALICE_LIST), { ownerUid: BOB }));
  await assertFails(updateDoc(doc(alice, ALICE_LIST), { name: 'stale timestamp' }));
});

test('owner cannot change immutable item listId, creation, or schema fields', async () => {
  await assertFails(updateDoc(doc(alice, ALICE_ITEM), { listId: 'other-list' }));
  await assertFails(updateDoc(doc(alice, ALICE_ITEM), { createdAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(alice, ALICE_ITEM), { schemaVersion: 2 }));
  await assertFails(updateDoc(doc(alice, ALICE_ITEM), { title: 'stale timestamp' }));
});

test('owner cannot backdate a tombstone or updatedAt', async () => {
  const old = Timestamp.fromDate(new Date('2020-01-01T00:00:00Z'));
  await assertFails(updateDoc(doc(alice, ALICE_LIST), { deletedAt: old }));
  await assertFails(updateDoc(doc(alice, ALICE_ITEM), { deletedAt: old, updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(alice, ALICE_ITEM), { updatedAt: old }));
});

test('owner cannot corrupt counters with negative, fractional, oversized, or inconsistent values', async () => {
  const list = doc(alice, ALICE_LIST);
  await assertFails(updateDoc(list, { totalItems: -1 }));
  await assertFails(updateDoc(list, { completedItems: -1 }));
  await assertFails(updateDoc(list, { totalItems: 1.5 }));
  await assertFails(updateDoc(list, { completedItems: 2147483648 }));
  await assertFails(updateDoc(list, { completedItems: 2147483647 }));
  await assertFails(updateDoc(list, { totalItems: increment(500) }));
  assert.equal((await assertSucceeds(getDoc(list))).data().totalItems, 32);
});

test('item writes cannot omit the corresponding parent counter movement', async () => {
  const list = doc(alice, `users/${ALICE}/lists/counter-witness`);
  const item = doc(alice, `users/${ALICE}/lists/counter-witness/items/item1`);
  await assertSucceeds(setDoc(list, validList('Counter witness')));
  await assertFails(setDoc(item, validItem('counter-witness')));
  const batch = writeBatch(alice);
  batch.set(item, validItem('counter-witness'));
  batch.update(list, { totalItems: increment(1) });
  await assertSucceeds(batch.commit());
  await assertFails(updateDoc(item, { isCompleted: true, updatedAt: serverTimestamp() }));
  await assertFails(deleteDoc(item));
  assert.equal((await assertSucceeds(getDoc(item))).exists(), true);
});

test('owner cannot inject an ownership field or delete a user document', async () => {
  await assertFails(updateDoc(doc(alice, ALICE_USER), { ownerUid: BOB }));
  await assertFails(deleteDoc(doc(alice, ALICE_USER)));
});

test('cross-user valid-shape creation and counter mutation are denied by owner path', async () => {
  await assertFails(setDoc(doc(alice, `users/${BOB}/lists/new-valid`), validList()));
  await assertFails(setDoc(doc(alice, `users/${BOB}/lists/list1/items/new-valid`), validItem()));
  await assertFails(updateDoc(doc(alice, BOB_LIST), { totalItems: increment(1) }));
});

// --- Cross-user access is denied ------------------------------------------

test("signed-in user A is DENIED read of user B's user document", async () => {
  await assertFails(getDoc(doc(alice, BOB_USER)));
});

test("signed-in user A is DENIED write of user B's user document", async () => {
  await assertFails(setDoc(doc(alice, BOB_USER), { displayName: 'pwned' }));
});

test("signed-in user A is DENIED read of user B's list", async () => {
  await assertFails(getDoc(doc(alice, BOB_LIST)));
});

test("signed-in user A is DENIED write of user B's list", async () => {
  await assertFails(setDoc(doc(alice, BOB_LIST), { name: 'pwned' }));
});

test("signed-in user A is DENIED read of user B's item", async () => {
  await assertFails(getDoc(doc(alice, BOB_ITEM)));
});

test("signed-in user A is DENIED write of user B's item", async () => {
  await assertFails(setDoc(doc(alice, BOB_ITEM), { title: 'pwned' }));
});

test("signed-in user A is DENIED creating a NEW document under user B's subtree", async () => {
  await assertFails(setDoc(doc(alice, `users/${BOB}/lists/injected`), { name: 'pwned' }));
});

// --- Unauthenticated access is denied -------------------------------------

test('unauthenticated request is DENIED read of a user document', async () => {
  await assertFails(getDoc(doc(unauth, ALICE_USER)));
});

test('unauthenticated request is DENIED read of a list', async () => {
  await assertFails(getDoc(doc(unauth, ALICE_LIST)));
});

test('unauthenticated request is DENIED read of an item', async () => {
  await assertFails(getDoc(doc(unauth, ALICE_ITEM)));
});

test('unauthenticated request is DENIED write of a list', async () => {
  await assertFails(setDoc(doc(unauth, ALICE_LIST), { name: 'anon' }));
});

// --- Deny-by-default for unmatched paths ----------------------------------

test('owner is DENIED an unmatched subcollection under their own user document', async () => {
  await assertFails(setDoc(doc(alice, `users/${ALICE}/secrets/s1`), { v: 1 }));
});

test('owner cannot read or overwrite a server-owned list cleanup claim or photo journal', async () => {
  await assertFails(getDoc(doc(alice, ALICE_CLEANUP_JOB)));
  await assertFails(setDoc(doc(alice, ALICE_CLEANUP_JOB), { claimedAt: 2 }));
  await assertFails(getDoc(doc(alice, ALICE_CLEANUP_PHOTO)));
  await assertFails(setDoc(doc(alice, ALICE_CLEANUP_PHOTO), { photoRef: 'replacement' }));
});

test('owner cannot recreate a list ID while its server cleanup claim exists', async () => {
  const list = doc(alice, `users/${ALICE}/lists/claimed-list`);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `users/${ALICE}/listCleanupJobs/claimed-list`), { claimedAt: 1 });
  });
  await assertFails(setDoc(list, validList('recreated')));
});

test('owner is DENIED an unmatched top-level collection', async () => {
  await assertFails(setDoc(doc(alice, 'config/global'), { v: 1 }));
});

test('owner is DENIED reading the top-level users collection document namespace of another shape', async () => {
  await assertFails(getDoc(doc(alice, `users/${ALICE}/lists/list1/items/item1/attachments/a1`)));
});
