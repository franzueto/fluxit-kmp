const { test } = require('node:test');
const assert = require('node:assert/strict');
const { cleanupExpiredData } = require('../index');

test('exports a daily UTC second-generation scheduled target', () => {
  const endpoint = cleanupExpiredData.__endpoint;
  assert.equal(endpoint.platform, 'gcfv2');
  assert.equal(endpoint.scheduleTrigger.schedule, '0 3 * * *');
  assert.equal(endpoint.scheduleTrigger.timeZone, 'Etc/UTC');
  assert.equal(endpoint.maxInstances, 1);
  assert.equal(endpoint.concurrency, 1);
});
