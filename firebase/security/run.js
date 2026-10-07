import { readFileSync, writeFileSync, existsSync, unlinkSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomUUID, randomBytes } from 'node:crypto';
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { initializeApp, deleteApp } from 'firebase/app';
import { getAuth, connectAuthEmulator, signInWithEmailAndPassword } from 'firebase/auth';
import { getFirestore, connectFirestoreEmulator, terminate, setLogLevel } from 'firebase/firestore';
import { getStorage, connectStorageEmulator, ref, uploadBytes, deleteObject, getMetadata } from 'firebase/storage';
import { root, liveConfig } from './config.js';
import { preflight, adminRequest } from './admin.js';
import { securitySuite } from './suite.js';
const require = createRequire(resolve(root, 'functions/package.json'));
const { initializeApp: initializeAdmin, deleteApp: deleteAdmin } = require('firebase-admin/app');
const { getAuth: adminAuth } = require('firebase-admin/auth');
const manifestPath = resolve(root, 'firebase.secsuite-run.json'); // covered by firebase.*.json ignore
const args = process.argv.slice(2);
let phase = 'configuration';
let passed = 0;
let cleanupArmed = false;
let manifest, adminApp, token, config;
const clients = [];
const pendingOperations = new Set();
const emulator = args.includes('--emulator');
const recovery = args.includes('--cleanup');
const safeCode = (e) => /^[a-zA-Z0-9_/-]+$/.test(String(e?.code ?? e?.message)) ? String(e.code ?? e.message) : 'ASSERTION_OR_ACCESS_FAILURE';
function save() { writeFileSync(manifestPath, JSON.stringify(manifest, null, 2), { mode: 0o600 }); }
function recordPhoto(path, uid) {
  if (!manifest.photos.some((p) => p.path === path)) { manifest.photos.push({ path, uid }); save(); }
}
function validateManifest(value) {
  if (value.version !== 1 || !/^[a-f0-9-]{36}$/.test(value.runId) || value.environment !== (emulator ? 'emulator' : 'development') ||
    value.projectId !== config.projectId || value.storageBucket !== config.storageBucket || value.users.length !== 2) throw new Error('INVALID_MANIFEST');
  for (const [i, user] of value.users.entries()) {
    if (user.uid !== `secsuite-${value.runId}-${i}` || user.email !== `secsuite-${value.runId}-${i}@example.com`) throw new Error('INVALID_MANIFEST_OWNER');
  }
  for (const photo of value.photos) {
    if (!value.users.some((u) => u.uid === photo.uid) || !photo.path.startsWith(`users/${photo.uid}/`) ||
      photo.path.includes('..') || !/^users\/[a-z0-9-]+\/[a-z0-9/-]+$/.test(photo.path)) throw new Error('INVALID_MANIFEST_PHOTO');
  }
}
async function client(name, user, password) {
  const app = initializeApp(config, `secsuite-${name}-${randomUUID()}`);
  const auth = getAuth(app), db = getFirestore(app), storage = getStorage(app);
  clients.push({ app, auth, db, storage });
  if (emulator) {
    const ports = JSON.parse(readFileSync(resolve(root, 'firebase.json'), 'utf8')).emulators;
    connectAuthEmulator(auth, `http://127.0.0.1:${ports.auth.port}`, { disableWarnings: true });
    connectFirestoreEmulator(db, '127.0.0.1', ports.firestore.port);
    connectStorageEmulator(storage, '127.0.0.1', ports.storage.port);
  }
  storage.maxUploadRetryTime = 15000; storage.maxOperationRetryTime = 15000;
  if (user) await signInWithEmailAndPassword(auth, user.email, password);
  return { app, auth, db, storage };
}
async function ownedAccount(user) {
  try {
    const account = await adminAuth(adminApp).getUser(user.uid);
    if (account.email !== user.email) throw new Error('ACCOUNT_IDENTITY_MISMATCH');
    return true;
  } catch (e) { if (e.code === 'auth/user-not-found') return false; throw e; }
}
async function cleanup() {
  validateManifest(manifest);
  const errors = [];
  for (const user of manifest.users) {
    try {
      // A crash may happen after createUser succeeds but before its response is received.
      // Recover only this run's exact UID with matching synthetic email; password stays in memory.
      const accountExists = await ownedAccount(user);
      const password = randomBytes(32).toString('base64url');
      if (accountExists) await adminAuth(adminApp).updateUser(user.uid, { password });
      else await adminAuth(adminApp).createUser({ uid: user.uid, email: user.email, password });
      // Recreating this run's absent synthetic UID lets its owner verify Storage
      // absence even if the process stopped after deleting Auth on a previous cleanup.
      const owner = await client('cleanup', user, password);
      for (const photo of manifest.photos.filter((p) => p.uid === user.uid)) {
        try { await deleteObject(ref(owner.storage, photo.path)); }
        catch (e) { if (e.code !== 'storage/object-not-found') throw e; }
        let absent = false;
        try { await getMetadata(ref(owner.storage, photo.path)); }
        catch (e) { if (e.code === 'storage/object-not-found') absent = true; else throw e; }
        if (!absent) throw new Error('PHOTO_CLEANUP_NOT_VERIFIED');
      }
      // No recursive deletes or collection-group reads. The exact planned document set
      // also covers unexpected successful denied writes. Children precede parents.
      const tails = ['lists/main/items/item', 'lists/main/items/cross-create', 'lists/main/items/invalid',
        'lists/main', 'lists/cross-create', 'lists/invalid', 'listCleanupJobs/main', ''];
      for (const tail of tails) {
        const document = `users/${user.uid}${tail ? `/${tail}` : ''}`;
        const origin = emulator ? `http://${process.env.FIRESTORE_EMULATOR_HOST}` : 'https://firestore.googleapis.com';
        const url = `${origin}/v1/projects/${config.projectId}/databases/(default)/documents/${document}`;
        await adminRequest(token, url, { method: 'DELETE', missingOK: true });
        if (await adminRequest(token, url, { missingOK: true }) !== null) throw new Error('DOCUMENT_CLEANUP_NOT_VERIFIED');
      }
      await adminAuth(adminApp).deleteUser(user.uid);
      if (await ownedAccount(user)) throw new Error('AUTH_CLEANUP_NOT_VERIFIED');
    } catch (e) { errors.push(safeCode(e)); }
  }
  if (errors.length) throw new Error(`CLEANUP_INCOMPLETE_${errors.length}`);
  unlinkSync(manifestPath);
  console.log(`PASS exact-fixture-cleanup-verified owners=${manifest.users.length} documentPaths=${manifest.users.length * 8} photoPaths=${manifest.photos.length} remaining=0`);
}

try {
  if (process.versions.node.split('.')[0] !== '22') throw new Error('NODE22_REQUIRED');
  if (emulator) {
    if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST || !process.env.FIREBASE_STORAGE_EMULATOR_HOST) throw new Error('EMULATORS_REQUIRED');
    config = { projectId: 'demo-fluxit', storageBucket: 'demo-fluxit.appspot.com', apiKey: 'secsuite-emulator-only' };
    token = 'owner';
  } else {
    if (!args.includes('--development') || (!recovery && !args.includes('--execute'))) throw new Error('EXPLICIT_DEVELOPMENT_EXECUTION_REQUIRED');
    config = liveConfig();
    execFileSync('git', ['diff', '--quiet', '6db83d997b7c2322e86501df874ec6d01dcdfb13', '--',
      'firebase.json', 'firestore.rules', 'firestore.indexes.json', 'storage.rules'], { cwd: root, stdio: 'ignore' });
    phase = 'read-only admin permissions';
    token = await preflight(config);
  }
  adminApp = initializeAdmin({ projectId: config.projectId, credential: { getAccessToken: async () => ({ access_token: token, expires_in: 3600 }) } }, `secsuite-admin-${randomUUID()}`);
  setLogLevel('silent');
  if (recovery) {
    phase = 'recovery cleanup';
    manifest = JSON.parse(readFileSync(manifestPath, 'utf8')); validateManifest(manifest); cleanupArmed = true;
  } else {
    if (existsSync(manifestPath)) throw new Error('EXISTING_MANIFEST_RUN_CLEANUP_FIRST');
    const runId = randomUUID();
    manifest = { version: 1, runId, environment: emulator ? 'emulator' : 'development', projectId: config.projectId,
      storageBucket: config.storageBucket, users: [0, 1].map((i) => ({ uid: `secsuite-${runId}-${i}`,
        email: `secsuite-${runId}-${i}@example.com` })), photos: [] };
    // Ensure identities do not exist before recording any intent. Never reclaim an existing UID.
    for (const user of manifest.users) if (await ownedAccount(user)) throw new Error('SYNTHETIC_UID_COLLISION');
    save(); cleanupArmed = true;
    phase = 'synthetic auth create';
    const owners = [];
    for (const user of manifest.users) {
      const password = randomBytes(32).toString('base64url');
      await adminAuth(adminApp).createUser({ uid: user.uid, email: user.email, password });
      if (emulator && args.includes('--verify-partial-auth-failure')) throw new Error('INJECTED_PARTIAL_AUTH_FAILURE');
      owners.push(await client('owner', user, password));
    }
    const anonymous = await client('anonymous');
    phase = 'authenticated client security suite';
    if (emulator && args.includes('--verify-pending-storage-failure')) {
      const path = `users/${manifest.users[0].uid}/items/item/pending`; recordPhoto(path, manifest.users[0].uid);
      const work = uploadBytes(ref(owners[0].storage, path), new Uint8Array(5 * 1024 * 1024), { contentType: 'image/png' });
      pendingOperations.add(work);
      work.then(() => console.log('Pending-upload settlement: completed-before-disposal'),
        (e) => console.log(`Pending-upload settlement: ${safeCode(e)}`));
      await new Promise((resolve) => setTimeout(resolve, 1));
      throw new Error('INJECTED_PENDING_STORAGE_FAILURE');
    }
    if (emulator && args.includes('--verify-cleanup-failure')) {
      const path = `users/${manifest.users[0].uid}/items/item/interrupted`; recordPhoto(path, manifest.users[0].uid);
      await uploadBytes(ref(owners[0].storage, path), new Uint8Array([137, 80, 78, 71]), { contentType: 'image/png' });
      throw new Error('INJECTED_FIXTURE_FAILURE');
    }
    await securitySuite({ a: owners[0], b: owners[1], anonymous, uidA: manifest.users[0].uid, uidB: manifest.users[1].uid,
      recordPhoto, pendingOperations, forgetPhoto: (path) => { manifest.photos = manifest.photos.filter((p) => p.path !== path); save(); },
      report: (label) => { passed++; console.log(`PASS ${label}`); } });
    console.log(`PASS ${manifest.environment} client-security-suite ${passed} assertions`);
  }
} catch (e) {
  console.log(`FAIL ${phase} ${safeCode(e)}; passed=${passed}`);
  process.exitCode = 1;
} finally {
  // Dispose original SDK instances before using fresh recovery clients. Storage
  // provider deletion cancels/aborts requests and refuses new work. Await the
  // underlying operations too: a timeout must not leave a detached upload/write.
  for (const c of clients) {
    await terminate(c.db).catch(() => {});
    await deleteApp(c.app).catch(() => {});
  }
  await Promise.allSettled([...pendingOperations]);
  if (cleanupArmed && manifest && adminApp) {
    try { phase = 'bounded cleanup'; await cleanup(); }
    catch (e) { console.log(`FAIL bounded-cleanup ${safeCode(e)}; retain ignored manifest for --cleanup`); process.exitCode = 1; }
  }
  for (const c of clients) { await terminate(c.db).catch(() => {}); await deleteApp(c.app).catch(() => {}); }
  if (adminApp) await deleteAdmin(adminApp).catch(() => {});
}
