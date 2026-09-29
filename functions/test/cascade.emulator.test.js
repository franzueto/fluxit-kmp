const { test } = require('node:test');
const assert = require('node:assert/strict');
const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore, Timestamp } = require('firebase-admin/firestore');
const { getStorage } = require('firebase-admin/storage');
const { RETENTION_MS, isReferenced } = require('../cleanup');
const { cascadeExpiredList, jobForList, purgeExpiredLists } = require('../cascade');

function emulator() {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  assert.ok(process.env.FIREBASE_STORAGE_EMULATOR_HOST);
  const app = initializeApp({ projectId: 'demo-fluxit', storageBucket: 'demo-fluxit.appspot.com' },
    `fb503-${Math.random().toString(36).slice(2)}`);
  return { app, db: getFirestore(app), bucket: getStorage(app).bucket() };
}

async function seedItems(db, listRef, count, uid, photoIndices = []) {
  const photos = [];
  let batch = db.batch();
  let inBatch = 0;
  for (let i = 0; i < count; i++) {
    const itemId = `item-${String(i).padStart(4, '0')}`;
    const photoRef = photoIndices.includes(i) ? `users/${uid}/items/${itemId}/photo` : null;
    batch.set(listRef.collection('items').doc(itemId), { photoRef, title: 'synthetic' });
    if (photoRef) photos.push(photoRef);
    if (++inBatch === 400) {
      await batch.commit();
      batch = db.batch();
      inBatch = 0;
    }
  }
  if (inBatch) await batch.commit();
  return photos;
}

async function removeFixture(db, listRef, bucket, photoRefs = []) {
  for (const name of ['items']) {
    while (true) {
      const page = await listRef.collection(name).limit(400).get();
      if (page.empty) break;
      const batch = db.batch();
      page.docs.forEach((doc) => batch.delete(doc.ref));
      await batch.commit();
    }
  }
  const jobRef = jobForList(listRef);
  while (true) {
    const page = await jobRef.collection('photos').limit(400).get();
    if (page.empty) break;
    const batch = db.batch();
    page.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
  }
  await jobRef.delete();
  await listRef.delete();
  await Promise.all(photoRefs.map((ref) => bucket.file(ref).delete({ ignoreNotFound: true })));
}

test('620-item list cascade crosses batch limit after durable parent claim', async () => {
  const { app, db, bucket } = emulator();
  const uid = `fb503-large-${process.pid}`;
  const listRef = db.doc(`users/${uid}/lists/list`);
  const cutoffMillis = Date.now() + 5_000;
  let photos = [];
  try {
    await listRef.set({ deletedAt: Timestamp.fromMillis(cutoffMillis - 1) });
    photos = await seedItems(db, listRef, 620, uid, [0, 199, 400, 619]);
    await Promise.all(photos.map((ref) => bucket.file(ref).save('synthetic', { resumable: false })));
    assert.equal(await cascadeExpiredList({
      db, bucket, listRef, cutoffMillis, isReferenced: (name) => isReferenced(db, name),
    }), true);
    assert.equal((await listRef.get()).exists, false);
    assert.equal((await jobForList(listRef).get()).exists, false);
    assert.equal((await listRef.collection('items').get()).size, 0);
    assert.equal((await jobForList(listRef).collection('photos').get()).size, 0);
    for (const photo of photos) assert.equal((await bucket.file(photo).exists())[0], false);
    assert.equal(await cascadeExpiredList({
      db, bucket, listRef, cutoffMillis, isReferenced: (name) => isReferenced(db, name),
    }), false);
  } finally {
    await removeFixture(db, listRef, bucket, photos);
    await deleteApp(app);
  }
});

test('Storage failure after partial photo progress keeps discoverable job for retry', async () => {
  const { app, db, bucket } = emulator();
  const uid = `fb503-retry-${process.pid}`;
  const listRef = db.doc(`users/${uid}/lists/list`);
  const cutoffMillis = Date.now() + 5_000;
  let photos = [];
  try {
    await listRef.set({ deletedAt: Timestamp.fromMillis(cutoffMillis - 1) });
    photos = await seedItems(db, listRef, 5, uid, [0, 1, 2, 3, 4]);
    await Promise.all(photos.map((ref) => bucket.file(ref).save('synthetic', { resumable: false })));
    let deletes = 0;
    const failingBucket = { file: (name) => {
      const real = bucket.file(name);
      return { getMetadata: () => real.getMetadata(), delete: async (options) => {
        if (++deletes === 2) throw new Error('synthetic Storage outage');
        return real.delete(options);
      } };
    } };
    await assert.rejects(cascadeExpiredList({
      db, bucket: failingBucket, listRef, cutoffMillis, isReferenced: (name) => isReferenced(db, name),
    }), /synthetic Storage outage/);
    assert.equal((await listRef.get()).exists, false);
    assert.equal((await jobForList(listRef).get()).exists, true);
    assert.equal((await listRef.collection('items').get()).size, 0);
    assert.equal((await jobForList(listRef).collection('photos').get()).size, 4);
    assert.equal(await purgeExpiredLists({
      db, bucket, cutoffMillis, isReferenced: (name) => isReferenced(db, name),
    }), 1);
    assert.equal((await listRef.get()).exists, false);
    assert.equal((await jobForList(listRef).get()).exists, false);
    for (const photo of photos) assert.equal((await bucket.file(photo).exists())[0], false);
  } finally {
    await removeFixture(db, listRef, bucket, photos);
    await deleteApp(app);
  }
});

test('interrupted page cascade resumes from the claimed job with no parent', async () => {
  const { app, db, bucket } = emulator();
  const uid = `fb503-page-retry-${process.pid}`;
  const listRef = db.doc(`users/${uid}/lists/list`);
  const cutoffMillis = Date.now() - RETENTION_MS;
  try {
    await listRef.set({ deletedAt: Timestamp.fromMillis(cutoffMillis - 1) });
    await seedItems(db, listRef, 205, uid);
    let interrupted = false;
    const failingDb = { runTransaction: async (body) => {
      const result = await db.runTransaction(body);
      if (!interrupted && result === 100) {
        interrupted = true;
        throw new Error('synthetic worker interruption');
      }
      return result;
    } };
    await assert.rejects(cascadeExpiredList({
      db: failingDb, bucket, listRef, cutoffMillis, isReferenced: (name) => isReferenced(db, name),
    }), /synthetic worker interruption/);
    assert.equal(interrupted, true);
    assert.equal((await listRef.get()).exists, false);
    assert.equal((await jobForList(listRef).get()).exists, true);
    assert.equal((await listRef.collection('items').get()).size, 105);
    assert.equal(await purgeExpiredLists({
      db, bucket, cutoffMillis, isReferenced: (name) => isReferenced(db, name),
    }), 1);
    assert.equal((await listRef.collection('items').get()).size, 0);
    assert.equal((await jobForList(listRef).get()).exists, false);
  } finally {
    await removeFixture(db, listRef, bucket);
    await deleteApp(app);
  }
});

test('restore before claim preserves every child; restore after claim cannot succeed across pages', async () => {
  const { app, db, bucket } = emulator();
  const uid = `fb503-restore-${process.pid}`;
  const listRef = db.doc(`users/${uid}/lists/list`);
  const cutoffMillis = Date.now() - RETENTION_MS;
  try {
    await listRef.set({ deletedAt: Timestamp.fromMillis(cutoffMillis - 1) });
    await seedItems(db, listRef, 205, uid);
    await listRef.update({ deletedAt: null });
    assert.equal(await cascadeExpiredList({
      db, bucket, listRef, cutoffMillis, isReferenced: (name) => isReferenced(db, name),
    }), false);
    assert.equal((await listRef.collection('items').get()).size, 205);
    await listRef.update({ deletedAt: Timestamp.fromMillis(cutoffMillis - 1) });
    let restoreRejected = false;
    const racingDb = { runTransaction: async (body) => {
      const result = await db.runTransaction(body);
      if (!restoreRejected && result === 100) {
        await assert.rejects(listRef.update({ deletedAt: null }), /NOT_FOUND|not found/i);
        restoreRejected = true;
      }
      return result;
    } };
    assert.equal(await cascadeExpiredList({
      db: racingDb, bucket, listRef, cutoffMillis, isReferenced: (name) => isReferenced(db, name),
    }), true);
    assert.equal(restoreRejected, true);
    assert.equal((await listRef.get()).exists, false);
    assert.equal((await listRef.collection('items').get()).size, 0);
    assert.equal((await jobForList(listRef).get()).exists, false);
  } finally {
    await removeFixture(db, listRef, bucket);
    await deleteApp(app);
  }
});

test('restore after candidate scan but before claim transaction preserves all children', async () => {
  const { app, db, bucket } = emulator();
  const uid = `fb503-claim-${process.pid}`;
  const listRef = db.doc(`users/${uid}/lists/list`);
  const cutoffMillis = Date.now() - RETENTION_MS;
  try {
    await listRef.set({ deletedAt: Timestamp.fromMillis(cutoffMillis - 1) });
    await seedItems(db, listRef, 3, uid);
    let raced = false;
    const racingDb = { runTransaction: async (body) => {
      if (!raced) {
        raced = true;
        await listRef.update({ deletedAt: null });
      }
      return db.runTransaction(body);
    } };
    assert.equal(await cascadeExpiredList({
      db: racingDb, bucket, listRef, cutoffMillis, isReferenced: (name) => isReferenced(db, name),
    }), false);
    assert.equal(raced, true);
    assert.equal((await listRef.get()).get('deletedAt'), null);
    assert.equal((await listRef.collection('items').get()).size, 3);
    assert.equal((await jobForList(listRef).get()).exists, false);
  } finally {
    await removeFixture(db, listRef, bucket);
    await deleteApp(app);
  }
});

test('restore attempted during photo journal cannot resurrect list or retain a missing photo', async () => {
  const { app, db, bucket } = emulator();
  const uid = `fb503-photo-race-${process.pid}`;
  const listRef = db.doc(`users/${uid}/lists/list`);
  const cutoffMillis = Date.now() + 5_000;
  let photos = [];
  try {
    await listRef.set({ deletedAt: Timestamp.fromMillis(cutoffMillis - 1) });
    photos = await seedItems(db, listRef, 2, uid, [0, 1]);
    await Promise.all(photos.map((ref) => bucket.file(ref).save('synthetic', { resumable: false })));
    let restoreRejected = false;
    const racingBucket = { file: (name) => {
      const real = bucket.file(name);
      return { getMetadata: async () => {
        if (!restoreRejected) {
          await assert.rejects(listRef.update({ deletedAt: null }), /NOT_FOUND|not found/i);
          restoreRejected = true;
        }
        return real.getMetadata();
      }, delete: (options) => real.delete(options) };
    } };
    assert.equal(await cascadeExpiredList({
      db, bucket: racingBucket, listRef, cutoffMillis, isReferenced: (name) => isReferenced(db, name),
    }), true);
    assert.equal(restoreRejected, true);
    assert.equal((await listRef.get()).exists, false);
    assert.equal((await jobForList(listRef).get()).exists, false);
    for (const photo of photos) assert.equal((await bucket.file(photo).exists())[0], false);
  } finally {
    await removeFixture(db, listRef, bucket, photos);
    await deleteApp(app);
  }
});
