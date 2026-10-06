const { test } = require('node:test');
const assert = require('node:assert/strict');

test('the local Functions emulator loads and invokes the scheduled cleanup target', async () => {
  const host = process.env.FIREBASE_EMULATOR_HUB || '127.0.0.1:4400';
  const hub = await fetch(`http://${host}/emulators`);
  assert.equal(hub.status, 200);
  const emulators = await hub.json();
  assert.ok(emulators.functions, 'Functions emulator must be running');
  assert.ok(emulators.pubsub, 'Pub/Sub emulator must be running for scheduled functions');
  assert.ok(emulators.firestore, 'Firestore emulator must be running');
  assert.ok(emulators.storage, 'Storage emulator must be running');

  // The CLI's local v2 schedule wrapper is exposed as cleanupExpiredData-0.
  // This URL is emulator-only; no public HTTP cleanup endpoint is deployed.
  const response = await fetch('http://127.0.0.1:5001/demo-fluxit/us-central1/cleanupExpiredData-0', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ scheduleTime: new Date().toISOString() }),
  });
  assert.equal(response.status, 200, await response.text());
});
