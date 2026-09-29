// FB-005 baseline Firestore Rules tests: owner-allowed, cross-user denied,
// unauthenticated denied, and unmatched paths denied (deny-by-default).
import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, deleteDoc, writeBatch, increment } from 'firebase/firestore';
import { createTestEnvironment, ALICE, BOB } from './helpers.js';

const ALICE_USER = `users/${ALICE}`;
const ALICE_LIST = `users/${ALICE}/lists/list1`;
const ALICE_ITEM = `users/${ALICE}/lists/list1/items/item1`;
const ALICE_CLEANUP_JOB = `users/${ALICE}/listCleanupJobs/seeded-claim`;
const ALICE_CLEANUP_PHOTO = `${ALICE_CLEANUP_JOB}/photos/item1`;
const BOB_USER = `users/${BOB}`;
const BOB_LIST = `users/${BOB}/lists/list1`;
const BOB_ITEM = `users/${BOB}/lists/list1/items/item1`;

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
    await setDoc(doc(db, BOB_LIST), { name: "bob's list" });
    await setDoc(doc(db, BOB_ITEM), { title: "bob's item" });
    await setDoc(doc(db, ALICE_USER), { displayName: 'alice' });
    await setDoc(doc(db, ALICE_LIST), { name: "alice's list" });
    await setDoc(doc(db, ALICE_ITEM), { title: "alice's item" });
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
  await assertSucceeds(setDoc(doc(alice, ALICE_LIST), { name: 'groceries' }));
});

test('owner can read own item', async () => {
  await assertSucceeds(getDoc(doc(alice, ALICE_ITEM)));
});

test('owner can write own item', async () => {
  await assertSucceeds(setDoc(doc(alice, ALICE_ITEM), { title: 'milk' }));
});

test('owner can atomically add an item and update its existing list counter', async () => {
  const batch = writeBatch(alice);
  batch.set(doc(alice, `users/${ALICE}/lists/list1/items/new-batch-item`), { title: 'milk' });
  batch.update(doc(alice, ALICE_LIST), { totalItems: increment(1) });
  await assertSucceeds(batch.commit());
});

test('owner can batch many item writes under the same existing list', async () => {
  const batch = writeBatch(alice);
  for (let i = 0; i < 30; i++) {
    batch.set(doc(alice, `users/${ALICE}/lists/list1/items/bulk-${i}`), { title: 'bulk' });
  }
  batch.update(doc(alice, ALICE_LIST), { totalItems: increment(30) });
  await assertSucceeds(batch.commit());
});

test('owner delayed item write is DENIED after its list parent is gone', async () => {
  const list = doc(alice, `users/${ALICE}/lists/removed-parent`);
  const item = doc(alice, `users/${ALICE}/lists/removed-parent/items/offline-item`);
  await assertSucceeds(setDoc(list, { name: 'temporary' }));
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await deleteDoc(doc(ctx.firestore(), `users/${ALICE}/lists/removed-parent`));
  });
  await assertFails(setDoc(item, { title: 'queued offline item' }));
});

test('owner cannot hard-delete a list and strand its item subcollection', async () => {
  const list = doc(alice, `users/${ALICE}/lists/list1`);
  await assertFails(deleteDoc(list));
  assert.equal((await assertSucceeds(getDoc(list))).exists(), true);
});

test('owner cannot batch-delete a list and create an orphan item', async () => {
  const list = doc(alice, `users/${ALICE}/lists/batch-parent`);
  const item = doc(alice, `users/${ALICE}/lists/batch-parent/items/orphan`);
  await assertSucceeds(setDoc(list, { name: 'temporary' }));
  const batch = writeBatch(alice);
  batch.delete(list);
  batch.set(item, { title: 'orphan' });
  await assertFails(batch.commit());
  assert.equal((await assertSucceeds(getDoc(list))).exists(), true);
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
  await assertFails(setDoc(list, { name: 'recreated' }));
});

test('owner is DENIED an unmatched top-level collection', async () => {
  await assertFails(setDoc(doc(alice, 'config/global'), { v: 1 }));
});

test('owner is DENIED reading the top-level users collection document namespace of another shape', async () => {
  await assertFails(getDoc(doc(alice, `users/${ALICE}/lists/list1/items/item1/attachments/a1`)));
});
