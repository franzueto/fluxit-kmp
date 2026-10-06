const { test } = require('node:test');
const assert = require('node:assert/strict');
const { validPhotoRef, PAGE_SIZE } = require('../cascade');

test('cascade page remains below Firestore batch limit and photo paths match owner/item', () => {
  assert.equal(PAGE_SIZE, 100);
  assert.equal(validPhotoRef('users/alice/items/item/photo', 'alice', 'item'), true);
  assert.equal(validPhotoRef(null, 'alice', 'item'), true);
  assert.equal(validPhotoRef('users/alice/lists/list/items/item/photo', 'alice', 'item'), false);
  assert.equal(validPhotoRef('users/bob/items/item/photo', 'alice', 'item'), false);
  assert.equal(validPhotoRef('users/alice/items/other/photo', 'alice', 'item'), false);
});
