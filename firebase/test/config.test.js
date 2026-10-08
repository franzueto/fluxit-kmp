// Emulator success cannot prove index coverage. Check the deployment
// contract separately, including the Phase 5 collection-group overrides.
import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { EMULATOR_PORTS } from './helpers.js';

const read = (path) => readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8');
const config = JSON.parse(read('firebase.json'));
const indexes = JSON.parse(read(config.firestore.indexes));

function fieldIndexes(group, field) {
  return indexes.fieldOverrides.find((entry) =>
    entry.collectionGroup === group && entry.fieldPath === field)?.indexes ?? [];
}

test('CLI uses the checked-in Rules and index config with the emulator-only default', () => {
  assert.equal(config.firestore.rules, 'firestore.rules');
  assert.equal(config.firestore.indexes, 'firestore.indexes.json');
  assert.equal(JSON.parse(read('.firebaserc')).projects.default, 'demo-fluxit');
  assert.ok(Array.isArray(indexes.indexes));
});

test('backend ordered/filtered group queries retain their ascending indexes', () => {
  for (const [group, field] of [
    ['listCleanupJobs', 'claimedAt'], ['lists', 'deletedAt'],
    ['items', 'deletedAt'], ['items', 'photoRef'],
  ]) {
    assert.ok(fieldIndexes(group, field).some((entry) =>
      entry.order === 'ASCENDING' && entry.queryScope === 'COLLECTION_GROUP'), `${group}.${field}`);
  }
});

test('mobile compound equality filters keep collection indexes available for merging', () => {
  for (const [group, field] of [
    ['lists', 'deletedAt'], ['items', 'deletedAt'], ['items', 'photoRef'], ['items', 'isCompleted'],
  ]) {
    // No exemption means the default ascending/descending indexes apply.
    // Reject a wildcard exemption too: it would disable inherited defaults.
    assert.equal(indexes.fieldOverrides.some((entry) =>
      entry.collectionGroup === group && entry.fieldPath === '*'), false, `${group} defaults disabled`);
    const overrides = indexes.fieldOverrides.filter((entry) =>
      entry.collectionGroup === group && entry.fieldPath === field);
    if (overrides.length) {
      for (const order of ['ASCENDING', 'DESCENDING']) {
        assert.ok(fieldIndexes(group, field).some((entry) =>
          entry.order === order && entry.queryScope === 'COLLECTION'), `${group}.${field} ${order}`);
      }
    }
  }
});

test('Android/iOS generated endpoint defaults, CLI, and Rules helpers agree', () => {
  const properties = new Map(read('gradle.properties').split(/\r?\n/)
    .filter((line) => line && !line.startsWith('#'))
    .map((line) => { const separator = line.indexOf('='); return [line.slice(0, separator), line.slice(separator + 1)]; }));
  for (const service of ['auth', 'firestore', 'storage']) {
    assert.equal(Number(properties.get(`fluxit.firebase.emulator.${service}.port`)), config.emulators[service].port, service);
    assert.equal(EMULATOR_PORTS[service].port, config.emulators[service].port, `${service} helper`);
  }
});

// Web Hosting (docs/web-app/PROGRESS.md, Phase 6). Checked with the CLI's own upload
// lister and the Hosting emulator's path matcher, over the file names the production
// bundle contains, so no build is needed.
const require = createRequire(import.meta.url);
const { listFiles } = require('firebase-tools/lib/listFiles.js');
const { configMatcher } = require('superstatic/lib/utils/patterns.js');

const WEB_BUNDLE = [
  'index.html', 'styles.css', 'web-shell.js', 'manifest.webmanifest',
  'composeApp.js', 'composeApp.js.LICENSE.txt', 'composeApp.js.map', 'firebase-bridge.mjs',
  '0c7a3ff214d646587a58.wasm', '89ef53602c28cdfe12c7.wasm',
  'icons/icon-192.png', 'icons/icon-512.png', 'icons/icon-maskable-512.png', 'icons/apple-touch-icon.png',
  'composeResources/fluxit.composeapp.generated.resources/values/strings.commonMain.cvr',
];
const UPLOADED = WEB_BUNDLE.filter((file) => !file.endsWith('.map') && file !== 'firebase-bridge.mjs');

function headersFor(path) {
  return config.hosting.headers
    .filter((rule) => configMatcher(path, rule))
    .flatMap((rule) => rule.headers);
}

test('Hosting deploys a freshly built production web bundle', () => {
  assert.equal(config.hosting.public, 'composeApp/build/dist/wasmJs/productionExecutable');
  assert.deepEqual(config.hosting.predeploy, ['./gradlew :composeApp:wasmJsBrowserDistribution']);
  assert.equal(config.hosting.rewrites, undefined);
});

test('Hosting uploads the bundle without source maps or the raw bridge module', () => {
  const dir = mkdtempSync(join(tmpdir(), 'fluxit-hosting-'));
  try {
    for (const file of WEB_BUNDLE) {
      mkdirSync(dirname(join(dir, file)), { recursive: true });
      writeFileSync(join(dir, file), '');
    }
    assert.deepEqual(listFiles(dir, config.hosting.ignore).sort(), [...UPLOADED].sort());
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('Hosting caches only the content-hashed Wasm files; everything else revalidates', () => {
  for (const path of ['/', ...UPLOADED.map((file) => `/${file}`)]) {
    const cacheControl = headersFor(path).filter((header) => header.key === 'Cache-Control');
    assert.equal(cacheControl.length, 1, `${path} has exactly one Cache-Control rule`);
    const expected = path.endsWith('.wasm') ? 'public, max-age=31536000, immutable' : 'no-cache';
    assert.equal(cacheControl[0].value, expected, path);
  }
});

test('Hosting sends a CSP that allows Wasm but not eval, and only the Firebase APIs', () => {
  for (const path of ['/', '/index.html', '/composeApp.js']) {
    const headers = new Map(headersFor(path).map((header) => [header.key, header.value]));
    assert.equal(headers.get('X-Content-Type-Options'), 'nosniff', path);
    // API key HTTP-referrer restrictions need the origin on cross-origin requests.
    assert.equal(headers.get('Referrer-Policy'), 'strict-origin-when-cross-origin', path);
    const csp = new Map(headers.get('Content-Security-Policy').split(';')
      .map((directive) => directive.trim().split(/\s+/))
      .map(([name, ...values]) => [name, values]));
    assert.deepEqual(csp.get('script-src'), ["'self'", "'wasm-unsafe-eval'"], path);
    assert.deepEqual(csp.get('connect-src'), [
      "'self'",
      'https://identitytoolkit.googleapis.com',
      'https://securetoken.googleapis.com',
      'https://firestore.googleapis.com',
      'https://firebasestorage.googleapis.com',
    ], path);
    for (const name of ['object-src', 'frame-ancestors']) {
      assert.deepEqual(csp.get(name), ["'none'"], `${path} ${name}`);
    }
  }
});

// The year-long immutable rule is only safe for content-hashed names. Checked on the
// built production bundle when one exists (run after wasmJsBrowserDistribution).
test('every Wasm file in a built production bundle is named by content hash', (t) => {
  const dist = new URL(`../../${config.hosting.public}/`, import.meta.url);
  if (!existsSync(dist)) {
    t.skip('no production bundle built');
    return;
  }
  const wasm = readdirSync(dist, { recursive: true }).filter((file) => String(file).endsWith('.wasm'));
  assert.ok(wasm.length > 0);
  for (const file of wasm) {
    assert.match(String(file), /^[0-9a-f]{20}\.wasm$/, `${file} would be cached for a year under a fixed name`);
  }
});
