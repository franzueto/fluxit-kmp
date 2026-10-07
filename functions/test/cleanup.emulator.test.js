const { test } = require('node:test');
const assert = require('node:assert/strict');
const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore, Timestamp } = require('firebase-admin/firestore');
const { getStorage } = require('firebase-admin/storage');
const { runCleanup, deleteExpiredItem, RETENTION_MS } = require('../cleanup');

test('real Firestore/Storage emulators: exact boundary, restore, and repeat run', async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  assert.ok(process.env.FIREBASE_STORAGE_EMULATOR_HOST);
  const app = initializeApp({ projectId: 'demo-fluxit', storageBucket: 'demo-fluxit.appspot.com' }, 'itemcleanup-test');
  const db = getFirestore(app);
  const bucket = getStorage(app).bucket();
  const nowMillis = Date.now();
  const cutoff = nowMillis - RETENTION_MS;
  const uid = `itemcleanup-${process.pid}`;
  const listRef = db.doc(`users/${uid}/lists/list`);
  const items = listRef.collection('items');
  const old = items.doc('old');
  const boundary = items.doc('boundary');
  const fresh = items.doc('fresh');
  const restored = items.doc('restored');
  const raced = items.doc('raced');
  const retained = items.doc('retained');
  const photo = `users/${uid}/items/retained/photo`;
  const recentOrphan = `users/${uid}/items/orphan/photo`;
  try {
    await listRef.set({ name: 'synthetic' });
    await Promise.all([
      old.set({ deletedAt: Timestamp.fromMillis(cutoff - 1) }),
      boundary.set({ deletedAt: Timestamp.fromMillis(cutoff) }),
      fresh.set({ deletedAt: Timestamp.fromMillis(cutoff + 1) }),
      restored.set({ deletedAt: null }),
      raced.set({ deletedAt: Timestamp.fromMillis(cutoff - 1) }),
      retained.set({ deletedAt: Timestamp.fromMillis(cutoff + 1), photoRef: photo }),
    ]);
    await bucket.file(photo).save('synthetic', { resumable: false });
    await bucket.file(recentOrphan).save('synthetic', { resumable: false });
    // A scan can see the old tombstone, then a client can restore it before
    // the delete transaction. The transaction must read the restored state.
    const staleCandidate = await raced.get();
    assert.equal(staleCandidate.get('deletedAt').toMillis(), cutoff - 1);
    await raced.update({ deletedAt: null });
    assert.equal(await deleteExpiredItem(db, staleCandidate.ref, cutoff), false);
    const first = await runCleanup({ db, bucket, nowMillis });
    assert.equal(first.deletedLists, 0);
    assert.equal(first.deletedItems, 2);
    assert.equal(first.deletedPhotos, 0);
    assert.equal((await old.get()).exists, false);
    assert.equal((await boundary.get()).exists, false);
    assert.equal((await fresh.get()).exists, true);
    assert.equal((await restored.get()).exists, true);
    assert.equal((await raced.get()).exists, true);
    assert.equal((await retained.get()).exists, true);
    assert.equal((await bucket.file(photo).exists())[0], true);
    assert.equal((await bucket.file(recentOrphan).exists())[0], true);
    assert.deepEqual(await runCleanup({ db, bucket, nowMillis }), { deletedLists: 0, deletedItems: 0, deletedPhotos: 0 });
  } finally {
    await Promise.all([old.delete(), boundary.delete(), fresh.delete(), restored.delete(), raced.delete(), retained.delete()]);
    await listRef.delete();
    await Promise.all([bucket.file(photo).delete({ ignoreNotFound: true }),
      bucket.file(recentOrphan).delete({ ignoreNotFound: true })]);
    await deleteApp(app);
  }
});

test('real Storage emulator: old orphan is deleted and active item photo is retained', async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  assert.ok(process.env.FIREBASE_STORAGE_EMULATOR_HOST);
  const app = initializeApp({ projectId: 'demo-fluxit', storageBucket: 'demo-fluxit.appspot.com' }, 'itemcleanup-photo-test');
  const db = getFirestore(app);
  const bucket = getStorage(app).bucket();
  const uid = `itemcleanup-photo-${process.pid}`;
  const item = db.doc(`users/${uid}/lists/list/items/item`);
  const referenced = `users/${uid}/items/item/photo`;
  const orphan = `users/${uid}/items/orphan/photo`;
  try {
    await item.set({ deletedAt: null, photoRef: referenced });
    await Promise.all([
      bucket.file(referenced).save('synthetic', { resumable: false }),
      bucket.file(orphan).save('synthetic', { resumable: false }),
    ]);
    const result = await runCleanup({ db, bucket, nowMillis: Date.now() + RETENTION_MS + 1_000 });
    assert.equal(result.deletedItems, 0);
    assert.equal(result.deletedPhotos, 1);
    assert.equal((await bucket.file(referenced).exists())[0], true);
    assert.equal((await bucket.file(orphan).exists())[0], false);
    assert.equal((await item.get()).exists, true);
  } finally {
    await item.delete();
    await Promise.all([bucket.file(referenced).delete({ ignoreNotFound: true }),
      bucket.file(orphan).delete({ ignoreNotFound: true })]);
    await deleteApp(app);
  }
});
