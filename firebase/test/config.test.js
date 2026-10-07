// Emulator success cannot prove index coverage. Check the deployment
// contract separately, including the Phase 5 collection-group overrides.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
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
