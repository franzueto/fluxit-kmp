const { test } = require('node:test');
const assert = require('node:assert/strict');
const { Timestamp } = require('firebase-admin/firestore');
const { RETENTION_DAYS, RETENTION_MS, isFluxPhotoPath,
  deleteExpiredItem, deleteOrphanPhoto } = require('../cleanup');

test('one 30-day horizon and exact Storage photo path', () => {
  assert.equal(RETENTION_DAYS, 30);
  assert.equal(RETENTION_MS, 30 * 24 * 60 * 60 * 1000);
  assert.equal(isFluxPhotoPath('users/alice/items/item/photo'), true);
  assert.equal(isFluxPhotoPath('users/alice/lists/list/items/item/photo'), false);
  assert.equal(isFluxPhotoPath('users/alice/items/item/photo/extra'), false);
});

test('transaction re-check skips an item restored after candidate selection', async () => {
  let deleted = false;
  const db = { runTransaction: async (body) => body({
    get: async () => ({ exists: true, get: () => null }),
    delete: () => { deleted = true; },
  }) };
  assert.equal(await deleteExpiredItem(db, {}, 1_000), false);
  assert.equal(deleted, false);
});

test('transaction re-check rejects a newer tombstone at the exact boundary', async () => {
  let deleted = false;
  const db = { runTransaction: async (body) => body({
    get: async () => ({ exists: true, get: () => Timestamp.fromMillis(1_001) }),
    delete: () => { deleted = true; },
  }) };
  assert.equal(await deleteExpiredItem(db, {}, 1_000), false);
  assert.equal(deleted, false);
});

test('a failed item transaction is safe to retry', async () => {
  let attempts = 0;
  let deleted = 0;
  const db = { runTransaction: async (body) => {
    attempts++;
    if (attempts === 1) throw new Error('firestore unavailable');
    return body({
      get: async () => ({ exists: true, get: () => Timestamp.fromMillis(999) }),
      delete: () => { deleted++; },
    });
  } };
  await assert.rejects(deleteExpiredItem(db, {}, 1_000), /firestore unavailable/);
  assert.equal(await deleteExpiredItem(db, {}, 1_000), true);
  assert.equal(deleted, 1);
});

function photoFixture({ createdAt, referenced = false, foreignReference = false, failure = null }) {
  let deletes = 0;
  let lookups = 0;
  const db = { collectionGroup: () => ({ where: () => ({ limit: () => ({ get: async () => {
    lookups++;
    const docs = referenced || foreignReference ? [{ ref: {
      path: foreignReference ? 'users/bob/lists/list/items/item' : 'users/alice/lists/list/items/item',
    } }] : [];
    return { docs, size: docs.length };
  } }) }) }) };
  const file = {
    name: 'users/alice/items/item/photo',
    getMetadata: async () => [{ timeCreated: new Date(createdAt).toISOString(), generation: '17' }],
    delete: async (options) => {
      assert.equal(options.ifGenerationMatch, '17');
      deletes++;
      if (failure) throw failure;
    },
  };
  return { db, file, get deletes() { return deletes; }, get lookups() { return lookups; } };
}

test('orphan sweep waits through boundary and counts tombstoned references', async () => {
  const newPhoto = photoFixture({ createdAt: 1_001 });
  assert.equal(await deleteOrphanPhoto(newPhoto.db, newPhoto.file, 1_000), false);
  assert.equal(newPhoto.lookups, 0);
  const referenced = photoFixture({ createdAt: 1_000, referenced: true });
  assert.equal(await deleteOrphanPhoto(referenced.db, referenced.file, 1_000), false);
  assert.equal(referenced.deletes, 0);
  const orphan = photoFixture({ createdAt: 1_000 });
  assert.equal(await deleteOrphanPhoto(orphan.db, orphan.file, 1_000), true);
  assert.equal(orphan.deletes, 1);
  const foreign = photoFixture({ createdAt: 1_000, foreignReference: true });
  assert.equal(await deleteOrphanPhoto(foreign.db, foreign.file, 1_000), true);
});

test('photo delete failure is retryable; a replaced generation is preserved', async () => {
  const unavailable = photoFixture({ createdAt: 900, failure: new Error('storage unavailable') });
  await assert.rejects(deleteOrphanPhoto(unavailable.db, unavailable.file, 1_000), /storage unavailable/);
  unavailable.file.delete = async () => {};
  assert.equal(await deleteOrphanPhoto(unavailable.db, unavailable.file, 1_000), true);
  const replaced = photoFixture({ createdAt: 900, failure: { code: 412 } });
  assert.equal(await deleteOrphanPhoto(replaced.db, replaced.file, 1_000), false);
});
