const { Timestamp } = require('firebase-admin/firestore');

const PAGE_SIZE = 100; // At most 200 writes (item delete + photo journal) per transaction.

function isExpiredList(snapshot, cutoffMillis) {
  const deletedAt = snapshot.exists && snapshot.get('deletedAt');
  return deletedAt instanceof Timestamp && deletedAt.toMillis() <= cutoffMillis;
}

function validPhotoRef(photoRef, uid, itemId) {
  if (photoRef == null) return true;
  if (typeof photoRef !== 'string') return false;
  const parts = photoRef.split('/');
  return parts.length === 5 && parts[0] === 'users' && parts[1] === uid &&
    parts[2] === 'items' && parts[3] === itemId && parts[4] !== '';
}

function jobForList(listRef) {
  if (!/^users\/[^/]+\/lists\/[^/]+$/.test(listRef.path)) {
    throw new Error(`Unexpected list path ${listRef.path}`);
  }
  return listRef.parent.parent.collection('listCleanupJobs').doc(listRef.id);
}

function listForJob(jobRef) {
  if (!/^users\/[^/]+\/listCleanupJobs\/[^/]+$/.test(jobRef.path)) {
    throw new Error(`Unexpected list cleanup job path ${jobRef.path}`);
  }
  return jobRef.parent.parent.collection('lists').doc(jobRef.id);
}

async function claimExpiredList(db, listRef, cutoffMillis) {
  const jobRef = jobForList(listRef);
  return db.runTransaction(async (transaction) => {
    const [list, job] = await Promise.all([
      transaction.get(listRef), transaction.get(jobRef),
    ]);
    if (job.exists || !isExpiredList(list, cutoffMillis)) return false;
    // An update-based restore committed first makes the list ineligible;
    // after this atomic claim, restore update fails with NOT_FOUND.
    transaction.create(jobRef, { claimedAt: Timestamp.now(), deletedAt: list.get('deletedAt') });
    transaction.delete(listRef);
    return true;
  });
}

async function moveItemPageToJournal(db, jobRef) {
  const listRef = listForJob(jobRef);
  const uid = listRef.parent.parent.id;
  const items = listRef.collection('items');
  const journals = jobRef.collection('photos');
  return db.runTransaction(async (transaction) => {
    const job = await transaction.get(jobRef);
    if (!job.exists) return 0;
    const page = await transaction.get(items.limit(PAGE_SIZE));
    for (const item of page.docs) {
      if (!validPhotoRef(item.get('photoRef'), uid, item.id)) {
        throw new Error(`Unsafe photoRef on ${item.ref.path}; cascade stopped`);
      }
    }
    for (const item of page.docs) {
      const photoRef = item.get('photoRef');
      if (photoRef) transaction.set(journals.doc(item.id), { photoRef });
      transaction.delete(item.ref);
    }
    return page.size;
  });
}

async function clearPhotoJournal(jobRef, bucket, cutoffMillis, isReferenced) {
  const uid = jobRef.parent.parent.id;
  const journals = jobRef.collection('photos');
  let last;
  while (true) {
    let query = journals.limit(PAGE_SIZE);
    if (last) query = query.startAfter(last);
    const page = await query.get();
    if (page.empty) break;
    for (const journal of page.docs) {
      const photoRef = journal.get('photoRef');
      if (!validPhotoRef(photoRef, uid, journal.id) || !photoRef) {
        throw new Error(`Invalid cleanup photo journal at ${journal.ref.path}`);
      }
      const file = bucket.file(photoRef);
      let metadata;
      try {
        [metadata] = await file.getMetadata();
      } catch (error) {
        if (error.code !== 404) throw error;
        await journal.ref.delete();
        continue;
      }
      const createdAt = Date.parse(metadata.timeCreated);
      if (!Number.isFinite(createdAt) || createdAt > cutoffMillis || !metadata.generation) continue;
      if (await isReferenced(photoRef)) {
        await journal.ref.delete();
        continue;
      }
      try {
        await file.delete({ ifGenerationMatch: metadata.generation, ignoreNotFound: true });
      } catch (error) {
        if (error.code === 412) continue; // Newer generation: retry after its grace period.
        throw error;
      }
      await journal.ref.delete();
    }
    if (page.size < PAGE_SIZE) break;
    last = page.docs.at(-1);
  }
}

async function finishEmptyJob(db, jobRef) {
  const listRef = listForJob(jobRef);
  return db.runTransaction(async (transaction) => {
    const job = await transaction.get(jobRef);
    if (!job.exists) return false;
    const items = await transaction.get(listRef.collection('items').limit(1));
    const journals = await transaction.get(jobRef.collection('photos').limit(1));
    if (!items.empty || !journals.empty) return false;
    transaction.delete(jobRef);
    return true;
  });
}

async function cascadeClaimedList({ db, bucket, jobRef, cutoffMillis, isReferenced }) {
  if (!(await jobRef.get()).exists) return false;
  while (await moveItemPageToJournal(db, jobRef)) { /* drain pages */ }
  await clearPhotoJournal(jobRef, bucket, cutoffMillis, isReferenced);
  return finishEmptyJob(db, jobRef);
}

async function cascadeExpiredList({ db, bucket, listRef, cutoffMillis, isReferenced }) {
  const jobRef = jobForList(listRef);
  if (!(await claimExpiredList(db, listRef, cutoffMillis)) && !(await jobRef.get()).exists) {
    return false;
  }
  return cascadeClaimedList({ db, bucket, jobRef, cutoffMillis, isReferenced });
}

async function purgeExpiredLists({ db, bucket, cutoffMillis, isReferenced }) {
  let completed = 0;
  // Resume jobs first: a deleted parent cannot appear in a tombstone query.
  let last;
  while (true) {
    let query = db.collectionGroup('listCleanupJobs')
      .orderBy('claimedAt').limit(PAGE_SIZE);
    if (last) query = query.startAfter(last);
    const page = await query.get();
    if (page.empty) break;
    for (const job of page.docs) {
      if (!/^users\/[^/]+\/listCleanupJobs\/[^/]+$/.test(job.ref.path)) continue;
      if (await cascadeClaimedList({ db, bucket, jobRef: job.ref, cutoffMillis, isReferenced })) completed++;
    }
    last = page.docs.at(-1);
  }
  last = undefined;
  while (true) {
    let query = db.collectionGroup('lists')
      .where('deletedAt', '<=', Timestamp.fromMillis(cutoffMillis))
      .orderBy('deletedAt').limit(PAGE_SIZE);
    if (last) query = query.startAfter(last);
    const page = await query.get();
    if (page.empty) break;
    for (const list of page.docs) {
      if (!/^users\/[^/]+\/lists\/[^/]+$/.test(list.ref.path)) continue;
      if (await cascadeExpiredList({ db, bucket, listRef: list.ref, cutoffMillis, isReferenced })) completed++;
    }
    last = page.docs.at(-1);
  }
  return completed;
}

module.exports = { PAGE_SIZE, validPhotoRef, jobForList, listForJob, claimExpiredList,
  moveItemPageToJournal, clearPhotoJournal, finishEmptyJob, cascadeClaimedList,
  cascadeExpiredList, purgeExpiredLists };
