const { Timestamp } = require('firebase-admin/firestore');
const { purgeExpiredLists } = require('./cascade');

// DEC-003b / DEC-003e-2: a single 30-day horizon for tombstones and uploads.
const RETENTION_DAYS = 30;
const RETENTION_MS = RETENTION_DAYS * 24 * 60 * 60 * 1000;
const PAGE_SIZE = 100;

function isFluxPhotoPath(name) {
  const parts = name.split('/');
  return parts.length === 5 && parts[0] === 'users' && parts[2] === 'items' &&
    parts[1] !== '' && parts[3] !== '' && parts[4] !== '';
}

function expired(timestamp, cutoffMillis) {
  return timestamp instanceof Timestamp && timestamp.toMillis() <= cutoffMillis;
}

async function deleteExpiredItem(db, ref, cutoffMillis) {
  // The transaction retries on a concurrent restore and commits only if the
  // latest deletedAt is still a timestamp at or before the cutoff.
  return db.runTransaction(async (transaction) => {
    const current = await transaction.get(ref);
    if (!current.exists || !expired(current.get('deletedAt'), cutoffMillis)) return false;
    transaction.delete(ref);
    return true;
  });
}

async function purgeExpiredItems(db, cutoffMillis) {
  let deleted = 0;
  let last;
  while (true) {
    let query = db.collectionGroup('items')
      .where('deletedAt', '<=', Timestamp.fromMillis(cutoffMillis))
      .orderBy('deletedAt').limit(PAGE_SIZE);
    if (last) query = query.startAfter(last);
    const page = await query.get();
    if (page.empty) break;
    for (const candidate of page.docs) {
      // Only the documented user/list/item hierarchy is managed by this job.
      if (!/^users\/[^/]+\/lists\/[^/]+\/items\/[^/]+$/.test(candidate.ref.path)) continue;
      if (await deleteExpiredItem(db, candidate.ref, cutoffMillis)) deleted++;
    }
    last = page.docs.at(-1);
  }
  return deleted;
}

async function isReferenced(db, objectName) {
  // Include active AND tombstoned items; a soft-deleted item can still be
  // restored during its 30-day window. Re-query immediately before deletion.
  // Only the owning user's matching item ID counts; a foreign or malformed
  // document must not pin somebody else's orphan forever.
  const [, uid, , itemId] = objectName.split('/');
  let last;
  while (true) {
    let query = db.collectionGroup('items').where('photoRef', '==', objectName).limit(PAGE_SIZE);
    if (last) query = query.startAfter(last);
    const page = await query.get();
    for (const doc of page.docs) {
      const parts = doc.ref.path.split('/');
      if (parts.length === 6 && parts[0] === 'users' && parts[1] === uid &&
          parts[2] === 'lists' && parts[3] && parts[4] === 'items' && parts[5] === itemId) {
        return true;
      }
    }
    if (page.size < PAGE_SIZE) return false;
    last = page.docs.at(-1);
  }
}

async function deleteOrphanPhoto(db, file, cutoffMillis) {
  if (!isFluxPhotoPath(file.name)) return false;
  let metadata;
  try {
    [metadata] = await file.getMetadata();
  } catch (error) {
    if (error.code === 404) return false;
    throw error;
  }
  const createdAt = Date.parse(metadata.timeCreated);
  if (!Number.isFinite(createdAt) || createdAt > cutoffMillis || !metadata.generation) return false;
  if (await isReferenced(db, file.name)) return false;
  try {
    // If an upload replaced this name after getMetadata, never delete its new
    // generation. A failed delete remains eligible for a later scheduled retry.
    await file.delete({ ifGenerationMatch: metadata.generation, ignoreNotFound: true });
    return true;
  } catch (error) {
    if (error.code === 412) return false;
    throw error;
  }
}

async function purgeOrphanPhotos(db, bucket, cutoffMillis) {
  let deleted = 0;
  let pageToken;
  do {
    const [files, next] = await bucket.getFiles({
      prefix: 'users/', autoPaginate: false, maxResults: PAGE_SIZE, pageToken,
    });
    for (const file of files) {
      if (await deleteOrphanPhoto(db, file, cutoffMillis)) deleted++;
    }
    pageToken = next?.pageToken;
  } while (pageToken);
  return deleted;
}

async function runCleanup({ db, bucket, nowMillis = Date.now() }) {
  const cutoffMillis = nowMillis - RETENTION_MS;
  // Cascade lists first so item photoRefs are journaled before the standalone
  // expired-item pass can remove those documents.
  const deletedLists = await purgeExpiredLists({
    db, bucket, cutoffMillis, isReferenced: (name) => isReferenced(db, name),
  });
  const deletedItems = await purgeExpiredItems(db, cutoffMillis);
  const deletedPhotos = await purgeOrphanPhotos(db, bucket, cutoffMillis);
  return { deletedLists, deletedItems, deletedPhotos };
}

module.exports = { RETENTION_DAYS, RETENTION_MS, isFluxPhotoPath, deleteExpiredItem,
  deleteOrphanPhoto, isReferenced, runCleanup };
