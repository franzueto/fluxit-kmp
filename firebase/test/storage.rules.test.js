// FB-005 baseline Cloud Storage Rules tests: owner-allowed, cross-user denied,
// unauthenticated denied, and unmatched object paths denied (deny-by-default).
import test, { before, after } from 'node:test';
import { assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { ref, uploadBytes, getBytes } from 'firebase/storage';
import { createTestEnvironment, ALICE, BOB } from './helpers.js';

const ALICE_PHOTO = `users/${ALICE}/items/item1/photo1.jpg`;
const BOB_PHOTO = `users/${BOB}/items/item1/photo1.jpg`;

const BYTES = new Uint8Array([0xff, 0xd8, 0xff, 0xdb]); // token JPEG-ish payload
const META = { contentType: 'image/jpeg' };

let testEnv;
let alice;
let unauth;

before(async () => {
  testEnv = await createTestEnvironment();
  await testEnv.clearStorage();

  // Seed both users' objects with Rules bypassed so that a denied read below is
  // a genuine `storage/unauthorized` Rules denial and not a 404 on a missing
  // object (which would make the assertion vacuous).
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const storage = ctx.storage();
    await uploadBytes(ref(storage, ALICE_PHOTO), BYTES, META);
    await uploadBytes(ref(storage, BOB_PHOTO), BYTES, META);
    await uploadBytes(ref(storage, `users/${ALICE}/stray.jpg`), BYTES, META);
    await uploadBytes(ref(storage, 'public/shared.jpg'), BYTES, META);
  });

  alice = testEnv.authenticatedContext(ALICE).storage();
  unauth = testEnv.unauthenticatedContext().storage();
});

after(async () => {
  await testEnv?.cleanup();
});

// --- Owner is allowed ------------------------------------------------------

test('owner can upload own item photo', async () => {
  await assertSucceeds(uploadBytes(ref(alice, ALICE_PHOTO), BYTES, META));
});

test('owner can read own item photo', async () => {
  await assertSucceeds(getBytes(ref(alice, ALICE_PHOTO)));
});

// --- Cross-user access is denied ------------------------------------------

test("signed-in user A is DENIED read of user B's item photo", async () => {
  await assertFails(getBytes(ref(alice, BOB_PHOTO)));
});

test("signed-in user A is DENIED overwrite of user B's item photo", async () => {
  await assertFails(uploadBytes(ref(alice, BOB_PHOTO), BYTES, META));
});

test("signed-in user A is DENIED uploading a NEW object under user B's path", async () => {
  await assertFails(uploadBytes(ref(alice, `users/${BOB}/items/item9/injected.jpg`), BYTES, META));
});

// --- Unauthenticated access is denied -------------------------------------

test('unauthenticated request is DENIED read of an item photo', async () => {
  await assertFails(getBytes(ref(unauth, ALICE_PHOTO)));
});

test('unauthenticated request is DENIED upload of an item photo', async () => {
  await assertFails(uploadBytes(ref(unauth, ALICE_PHOTO), BYTES, META));
});

// --- Deny-by-default for unmatched object paths ---------------------------

test('owner is DENIED read of an object outside the users/{uid}/items/{itemId}/{photoId} shape', async () => {
  await assertFails(getBytes(ref(alice, `users/${ALICE}/stray.jpg`)));
});

test('owner is DENIED upload to an object path outside the owner-scoped shape', async () => {
  await assertFails(uploadBytes(ref(alice, `users/${ALICE}/stray2.jpg`), BYTES, META));
});

test('owner is DENIED read of a non-user-scoped object path', async () => {
  await assertFails(getBytes(ref(alice, 'public/shared.jpg')));
});
