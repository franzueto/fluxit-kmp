// Cloud Storage Rules matrix: owner-only, exact paths, image MIME/size,
// create-only uploads, and owner deletion (including legacy objects).
import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { ref, uploadBytes, getBytes, getMetadata, deleteObject } from 'firebase/storage';
import { createTestEnvironment, ALICE, BOB } from './helpers.js';

const ALICE_PHOTO = `users/${ALICE}/items/item1/photo1.jpg`;
const BOB_PHOTO = `users/${BOB}/items/item1/photo1.jpg`;
const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

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
    await uploadBytes(ref(storage, `users/${ALICE}/items/item1/legacy`), BYTES);
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
  const path = `users/${ALICE}/items/item1/new-photo`;
  await assertSucceeds(uploadBytes(ref(alice, path), BYTES, META));
  assert.equal((await assertSucceeds(getMetadata(ref(alice, path)))).contentType, 'image/jpeg');
});

test('owner can read own item photo', async () => {
  await assertSucceeds(getBytes(ref(alice, ALICE_PHOTO)));
});

test('owner can create JPEG, PNG, and WebP with the MIME metadata sent by mobile clients', async () => {
  for (const [suffix, contentType, bytes] of [
    ['jpeg', 'image/jpeg', BYTES],
    ['png', 'image/png', new Uint8Array([0x89, 0x50, 0x4e, 0x47])],
    ['webp', 'image/webp', new Uint8Array([0x52, 0x49, 0x46, 0x46])],
  ]) {
    const object = ref(alice, `users/${ALICE}/items/item1/mobile-${suffix}`);
    await assertSucceeds(uploadBytes(object, bytes, { contentType }));
    assert.equal((await assertSucceeds(getMetadata(object))).contentType, contentType);
  }
});

test('owner can upload exactly 5 MiB, but a larger image is denied by Rules', async () => {
  await assertSucceeds(uploadBytes(ref(alice, `users/${ALICE}/items/item1/at-limit`),
    new Uint8Array(MAX_UPLOAD_BYTES), META));
  await assertDenied(uploadBytes(ref(alice, `users/${ALICE}/items/item1/over-limit`),
    new Uint8Array(MAX_UPLOAD_BYTES + 1), META));
});

test('owner cannot upload empty, missing-MIME, or non-image content', async () => {
  await assertDenied(uploadBytes(ref(alice, `users/${ALICE}/items/item1/empty`),
    new Uint8Array(0), META));
  await assertDenied(uploadBytes(ref(alice, `users/${ALICE}/items/item1/no-mime`), BYTES));
  await assertDenied(uploadBytes(ref(alice, `users/${ALICE}/items/item1/text`),
    BYTES, { contentType: 'text/plain' }));
  await assertDenied(uploadBytes(ref(alice, `users/${ALICE}/items/item1/svg`),
    BYTES, { contentType: 'image/svg+xml' }));
});

test('owner cannot overwrite an existing photo, including with valid image metadata', async () => {
  await assertDenied(uploadBytes(ref(alice, ALICE_PHOTO), BYTES, META));
});

test('owner can delete a legacy object without image MIME metadata', async () => {
  const object = ref(alice, `users/${ALICE}/items/item1/legacy`);
  assert.equal((await assertSucceeds(getMetadata(object))).contentType, 'application/octet-stream');
  await assertSucceeds(deleteObject(object));
});

// --- Cross-user access is denied ------------------------------------------

test("signed-in user A is DENIED read of user B's item photo", async () => {
  await assertFails(getBytes(ref(alice, BOB_PHOTO)));
});

test("signed-in user A is DENIED overwrite of user B's item photo", async () => {
  await assertFails(uploadBytes(ref(alice, BOB_PHOTO), BYTES, META));
});

test("signed-in user A is DENIED deletion of user B's item photo", async () => {
  await assertDenied(deleteObject(ref(alice, BOB_PHOTO)));
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

async function assertDenied(promise) {
  await assert.rejects(promise, (error) => error?.code === 'storage/unauthorized');
}
