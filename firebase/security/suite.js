// Every acceptance operation uses the authenticated Firebase client SDK.
import assert from 'node:assert/strict';
import { collection, doc, setDoc, updateDoc, deleteDoc, getDocFromServer, getDocsFromServer,
  query, where, limit, writeBatch, increment, serverTimestamp } from 'firebase/firestore';
import { ref, uploadBytes, getBytes, getMetadata, deleteObject } from 'firebase/storage';

export async function securitySuite({ a, b, anonymous, uidA, uidB, recordPhoto, forgetPhoto, pendingOperations, report }) {
  const path = (uid, tail = '') => `users/${uid}${tail ? `/${tail}` : ''}`;
  const list = () => ({ name: 'synthetic', icon: 'CART', color: 'ORANGE', createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(), deletedAt: null, totalItems: 0, completedItems: 0, schemaVersion: 1 });
  const item = () => ({ listId: 'main', title: 'synthetic', description: null, photoRef: null,
    isCompleted: false, createdAt: serverTimestamp(), updatedAt: serverTimestamp(), deletedAt: null, schemaVersion: 1 });
  const check = async (label, operation) => {
    let timer;
    const work = Promise.resolve().then(operation);
    pendingOperations.add(work);
    work.then(() => pendingOperations.delete(work), () => pendingOperations.delete(work));
    try { await Promise.race([work, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('CLIENT_OPERATION_TIMEOUT')), 30000);
    })]); report(label); } finally { clearTimeout(timer); }
  };
  const denied = async (label, operation, code = 'permission-denied') => check(label, async () => {
    let failure; try { await operation(); } catch (e) { failure = e; }
    assert.equal(failure?.code, code, 'Expected an authorization denial');
  });
  const listRef = (client, uid, id = 'main') => doc(client.db, path(uid, `lists/${id}`));
  const itemRef = (client, uid, id = 'item') => doc(client.db, path(uid, `lists/main/items/${id}`));
  const createItem = async (client, uid, id, values = {}) => {
    const batch = writeBatch(client.db);
    batch.set(itemRef(client, uid, id), { ...item(), ...values });
    batch.update(listRef(client, uid), { totalItems: increment(1) });
    await batch.commit();
  };
  const changeItem = async (client, uid, values, total = 0, completed = 0, id = 'item') => {
    const batch = writeBatch(client.db);
    batch.update(itemRef(client, uid, id), { ...values, updatedAt: serverTimestamp() });
    if (total || completed) batch.update(listRef(client, uid),
      { totalItems: increment(total), completedItems: increment(completed) });
    await batch.commit();
  };
  const small = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]);
  const photo = (uid, id) => { const value = path(uid, `items/item/${id}`); recordPhoto(value, uid); return value; };
  // Prove authenticated owner teardown before the larger upload/denial matrix.
  const sentinel = photo(uidA, 'cleanup-sentinel');
  await check('owner storage cleanup sentinel upload', () => uploadBytes(ref(a.storage, sentinel), small, { contentType: 'image/png' }));
  await check('owner storage cleanup sentinel read', async () => assert.equal((await getBytes(ref(a.storage, sentinel))).byteLength, small.length));
  await check('owner storage cleanup sentinel delete verified', async () => {
    await deleteObject(ref(a.storage, sentinel));
    let code; try { await getMetadata(ref(a.storage, sentinel)); } catch (e) { code = e.code; }
    assert.equal(code, 'storage/object-not-found');
  });
  for (const [client, uid, name] of [[a, uidB, 'A to B'], [b, uidA, 'B to A'], [anonymous, uidA, 'anonymous']]) {
    await denied(`${name} fresh profile create denied`, () => setDoc(doc(client.db, path(uid)), { displayName: 'synthetic' }));
  }
  for (const [client, uid, name] of [[a, uidA, 'A'], [b, uidB, 'B']]) {
    await check(`${name} owner profile create`, () => setDoc(doc(client.db, path(uid)), { displayName: 'synthetic' }));
    await check(`${name} owner list create`, () => setDoc(listRef(client, uid), list()));
    await check(`${name} owner item create and counter`, () => createItem(client, uid, 'item'));
    await check(`${name} owner profile list item server reads`, async () => {
      for (const target of [doc(client.db, path(uid)), listRef(client, uid), itemRef(client, uid)]) assert.ok((await getDocFromServer(target)).exists());
    });
  }
  await check('owner profile update', () => updateDoc(doc(a.db, path(uidA)), { displayName: 'updated synthetic' }));
  await check('owner list update', () => updateDoc(listRef(a, uidA), { name: 'updated synthetic', updatedAt: serverTimestamp() }));
  await check('owner item update', () => changeItem(a, uidA, { title: 'updated synthetic' }));
  for (const [client, targetUid, name] of [[a, uidB, 'A to B'], [b, uidA, 'B to A'], [anonymous, uidA, 'anonymous']]) {
    for (const [target, data, category] of [[doc(client.db, path(targetUid)), { displayName: 'synthetic' }, 'profile'],
      [listRef(client, targetUid), list(), 'list'], [itemRef(client, targetUid), item(), 'item']]) {
      await denied(`${name} ${category} read denied`, () => getDocFromServer(target));
      await denied(`${name} ${category} update denied`, () => updateDoc(target, category === 'profile' ? data : { updatedAt: serverTimestamp() }));
      await denied(`${name} ${category} delete denied`, () => deleteDoc(target));
    }
    await denied(`${name} existing profile set denied`, () => setDoc(doc(client.db, path(targetUid)), { displayName: 'synthetic' }));
    await denied(`${name} list create denied`, () => setDoc(listRef(client, targetUid, 'cross-create'), list()));
    await denied(`${name} item create denied`, () => createItem(client, targetUid, 'cross-create'));
    await denied(`${name} list query denied`, () => getDocsFromServer(collection(client.db, path(targetUid, 'lists'))));
    await denied(`${name} item query denied`, () => getDocsFromServer(collection(client.db, path(targetUid, 'lists/main/items'))));
  }
  await denied('owner profile hard delete denied', () => deleteDoc(doc(a.db, path(uidA))));
  await denied('owner list hard delete denied', () => deleteDoc(listRef(a, uidA)));
  await denied('owner invalid list schema denied', () => setDoc(listRef(a, uidA, 'invalid'), { ...list(), unexpected: true }));
  await denied('owner immutable list timestamp denied', () => updateDoc(listRef(a, uidA), { createdAt: serverTimestamp() }));
  await denied('owner invalid item schema denied', () => createItem(a, uidA, 'invalid', { title: '' }));
  await denied('owner item creation without counter denied', () => setDoc(itemRef(a, uidA, 'invalid'), item()));
  await denied('owner foreign photoRef denied', () => changeItem(a, uidA, { photoRef: path(uidB, 'items/item/photo') }));
  await denied('owner private cleanup job read denied', () => getDocFromServer(doc(a.db, path(uidA, 'listCleanupJobs/main'))));
  await denied('owner private cleanup job create denied', () => setDoc(doc(a.db, path(uidA, 'listCleanupJobs/main')), { claimedAt: serverTimestamp() }));
  await check('owner completion and counter', () => changeItem(a, uidA, { isCompleted: true }, 0, 1));
  await check('deployed owner list collection query', async () => {
    const result = await getDocsFromServer(collection(a.db, path(uidA, 'lists'))); assert.equal(result.size, 1);
  });
  await check('deployed owner item collection query', async () => {
    const result = await getDocsFromServer(collection(a.db, path(uidA, 'lists/main/items'))); assert.equal(result.size, 1);
  });
  await check('deployed clearCompleted equalities limit query', async () => {
    const result = await getDocsFromServer(query(collection(a.db, path(uidA, 'lists/main/items')),
      where('isCompleted', '==', true), where('deletedAt', '==', null), limit(400)));
    assert.equal(result.size, 1); assert.equal(result.docs[0].id, 'item');
  });
  await check('owner item soft delete and counters', () => changeItem(a, uidA, { deletedAt: serverTimestamp() }, -1, -1));
  await check('clearCompleted query excludes deleted item', async () => {
    assert.equal((await getDocsFromServer(query(collection(a.db, path(uidA, 'lists/main/items')),
      where('isCompleted', '==', true), where('deletedAt', '==', null), limit(400)))).size, 0);
  });
  await check('owner item restore and counters', () => changeItem(a, uidA, { deletedAt: null }, 1, 1));
  await check('owner list soft delete', () => updateDoc(listRef(a, uidA), { deletedAt: serverTimestamp(), updatedAt: serverTimestamp() }));
  await check('owner deleted list server read', async () => assert.ok((await getDocFromServer(listRef(a, uidA))).data().deletedAt));
  await check('owner list restore', () => updateDoc(listRef(a, uidA), { deletedAt: null, updatedAt: serverTimestamp() }));
  await check('owner item hard delete with counters', async () => {
    const batch = writeBatch(a.db); batch.delete(itemRef(a, uidA));
    batch.update(listRef(a, uidA), { totalItems: increment(-1), completedItems: increment(-1) }); await batch.commit();
    assert.equal((await getDocFromServer(itemRef(a, uidA))).exists(), false);
  });
  for (const [id, mime, bytes] of [['jpeg', 'image/jpeg', small], ['png', 'image/png', small],
    ['webp', 'image/webp', small], ['max-size', 'image/png', new Uint8Array(5 * 1024 * 1024)]]) {
    const target = photo(uidA, id);
    await check(`owner ${id} upload allowed`, () => uploadBytes(ref(a.storage, target), bytes, { contentType: mime }));
    await check(`owner ${id} read allowed`, async () => assert.equal((await getBytes(ref(a.storage, target))).byteLength, bytes.length));
  }
  const bobTarget = photo(uidB, 'png');
  await check('B owner photo upload allowed', () => uploadBytes(ref(b.storage, bobTarget), small, { contentType: 'image/png' }));
  await check('B owner photo read allowed', async () => assert.equal((await getBytes(ref(b.storage, bobTarget))).byteLength, small.length));
  await denied('A to B photo read denied', () => getBytes(ref(a.storage, bobTarget)), 'storage/unauthorized');
  await denied('A to B photo create denied', () => uploadBytes(ref(a.storage, photo(uidB, 'cross-create')), small,
    { contentType: 'image/png' }), 'storage/unauthorized');
  await denied('A to B photo overwrite denied', () => uploadBytes(ref(a.storage, bobTarget), small,
    { contentType: 'image/png' }), 'storage/unauthorized');
  await denied('A to B photo delete denied', () => deleteObject(ref(a.storage, bobTarget)), 'storage/unauthorized');
  await check('B owner photo preserved after denials', async () => assert.equal((await getBytes(ref(b.storage, bobTarget))).byteLength, small.length));
  await check('B owner photo delete allowed', () => deleteObject(ref(b.storage, bobTarget)));
  const target = photo(uidA, 'png');
  await denied('owner overwrite denied', () => uploadBytes(ref(a.storage, target), small, { contentType: 'image/png' }), 'storage/unauthorized');
  for (const [client, name] of [[b, 'cross-user'], [anonymous, 'anonymous']]) {
    await denied(`${name} photo read denied`, () => getBytes(ref(client.storage, target)), 'storage/unauthorized');
    await denied(`${name} photo create denied`, () => uploadBytes(ref(client.storage, photo(uidA, `${name}-create`)), small,
      { contentType: 'image/png' }), 'storage/unauthorized');
    await denied(`${name} photo overwrite denied`, () => uploadBytes(ref(client.storage, target), small,
      { contentType: 'image/png' }), 'storage/unauthorized');
    await denied(`${name} photo delete denied`, () => deleteObject(ref(client.storage, target)), 'storage/unauthorized');
  }
  for (const [id, bytes, metadata] of [['empty', new Uint8Array(), { contentType: 'image/png' }],
    ['oversize', new Uint8Array(5 * 1024 * 1024 + 1), { contentType: 'image/png' }],
    ['bad-mime', small, { contentType: 'text/plain' }], ['missing-mime', small, {}]]) {
    await denied(`owner ${id} upload denied`, () => uploadBytes(ref(a.storage, photo(uidA, id)), bytes, metadata), 'storage/unauthorized');
  }
  for (const [suffix, label] of [['items/item/nested/photo', 'nested'], ['other/photo', 'wrong-folder']]) {
    const invalid = path(uidA, suffix); recordPhoto(invalid, uidA);
    await denied(`owner ${label} path denied`, () => uploadBytes(ref(a.storage, invalid), small, { contentType: 'image/png' }), 'storage/unauthorized');
    forgetPhoto(invalid); // definitive server unauthorized response created no object
  }
  await check('denied operations preserved owner photo', async () => assert.equal((await getBytes(ref(a.storage, target))).byteLength, small.length));
  await check('owner photo delete allowed', () => deleteObject(ref(a.storage, target)));
}
