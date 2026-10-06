// FB-603: backend group query results/cursors, including equal values across
// users and pages. The emulator does not enforce compound indexes; the separate
// firebase/test/config.test.js checks the checked-in index contract.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore, Timestamp } = require('firebase-admin/firestore');
const { isReferenced } = require('../cleanup');
const { PAGE_SIZE } = require('../cascade');

async function fixture(name, body) {
  // Never fall through to real Admin SDK credentials/endpoints.
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  const app = initializeApp({ projectId: 'demo-fluxit' }, `fb603-${name}`);
  const db = getFirestore(app);
  const documents = [];
  try {
    await body(db, (path, data) => {
      const ref = db.doc(path);
      documents.push({ ref, data });
      return ref;
    }, async () => {
      const batch = db.batch();
      documents.forEach(({ ref, data }) => batch.set(ref, data));
      await batch.commit();
    });
  } finally {
    const batch = db.batch();
    documents.forEach(({ ref }) => batch.delete(ref));
    await batch.commit();
    await deleteApp(app);
  }
}

async function pages(base) {
  const result = [];
  let last;
  for (;;) {
    const page = await (last ? base.startAfter(last) : base).get();
    if (page.empty) return result;
    result.push(page.docs);
    last = page.docs.at(-1);
  }
}

for (const group of ['lists', 'items']) {
  test(`${group} deletedAt range/order query traverses tied cutoff timestamps across owners`, async () => {
    await fixture(group, async (db, add, commit) => {
      const expected = [];
      const root = `users/fb603-${group}-${process.pid}`;
      const path = (i) => {
        const user = `${root}-${i % 2}`;
        const id = String(i).padStart(3, '0');
        return group === 'lists' ? `${user}/lists/list-${id}` : `${user}/lists/list-${id}/items/item`;
      };
      const cutoff = Timestamp.fromMillis(1_000);
      const older = add(path(0), { deletedAt: Timestamp.fromMillis(999) });
      for (let i = 1; i <= 101; i++) expected.push(add(path(i), { deletedAt: cutoff }).path);
      expected.sort();
      expected.unshift(older.path);
      add(path(102), { deletedAt: Timestamp.fromMillis(1_001) });
      add(path(103), { deletedAt: null });
      add(path(104), { title: 'missing tombstone' });
      await commit();
      // Exact cleanup.js/cascade.js shape, including snapshot-based cursors.
      const result = await pages(db.collectionGroup(group).where('deletedAt', '<=', cutoff)
        .orderBy('deletedAt').limit(PAGE_SIZE));
      assert.deepEqual(result.map((page) => page.length), [100, 2]);
      assert.deepEqual(result.flat().map((entry) => entry.ref.path), expected);
    });
  });
}

test('claimedAt job discovery traverses multiple pages and excludes unindexed missing fields', async () => {
  await fixture('jobs', async (db, add, commit) => {
    const expected = [];
    for (let i = 0; i < 102; i++) {
      expected.push(add(`users/fb603-jobs-${process.pid}-${i % 2}/listCleanupJobs/job-${String(i).padStart(3, '0')}`,
        { claimedAt: Timestamp.fromMillis(1_000) }).path);
    }
    add(`users/fb603-jobs-${process.pid}/listCleanupJobs/missing`, { listId: 'synthetic' });
    await commit();
    const result = await pages(db.collectionGroup('listCleanupJobs').orderBy('claimedAt').limit(PAGE_SIZE));
    assert.deepEqual(result.map((page) => page.length), [100, 2]);
    assert.deepEqual(result.flat().map((entry) => entry.ref.path), expected.sort());
  });
});

test('photoRef equality query finds an owning tombstoned reference after a full foreign page', async () => {
  await fixture('photo', async (db, add, commit) => {
    const owner = `zz-fb603-owner-${process.pid}`;
    const photoRef = `users/${owner}/items/target/photo`;
    for (let i = 0; i < 101; i++) {
      add(`users/aa-fb603-foreign-${process.pid}/lists/list-${String(i).padStart(3, '0')}/items/target`, { photoRef });
    }
    const owningItem = add(`users/${owner}/lists/list/items/target`,
      { photoRef, deletedAt: Timestamp.fromMillis(1_000) });
    add(`users/${owner}/lists/list/items/other`, { photoRef: `${photoRef}-other` });
    await commit();
    const first = await db.collectionGroup('items').where('photoRef', '==', photoRef).limit(PAGE_SIZE).get();
    assert.equal(first.size, 100);
    assert.equal(first.docs.some((entry) => entry.ref.path === owningItem.path), false);
    // Execute production isReferenced, whose owning-path check must continue
    // past foreign matches and retain references during the restore window.
    assert.equal(await isReferenced(db, photoRef), true);
    await owningItem.delete();
    assert.equal(await isReferenced(db, photoRef), false);
  });
});
