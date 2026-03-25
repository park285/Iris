import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildPendingHintPath,
  buildClaimedHintPath,
  buildDoneHintPath,
  encodeThreadHint,
  extractTokenFromImageName,
  planHintClaim,
  parseThreadHintJson,
} from '../shared/hint.js';

test('parses thread hint json preserving string threadId', () => {
  const parsed = parseThreadHintJson(
    '{"token":"tok123","room":"18476130232878491","threadId":"3803466729815130113","threadScope":2,"createdAt":1711280000}',
  );

  assert.equal(parsed.token, 'tok123');
  assert.equal(parsed.room, '18476130232878491');
  assert.equal(parsed.threadId, '3803466729815130113');
  assert.equal(parsed.threadScope, 2);
  assert.equal(parsed.createdAt, 1711280000);
});

test('throws on malformed thread hint json', () => {
  assert.throws(() => parseThreadHintJson('{"token":"tok123"'));
});

test('extracts token from tokenized image file name', () => {
  assert.equal(extractTokenFromImageName('iris-graft-tok123-0.jpg'), 'tok123');
  assert.equal(extractTokenFromImageName('/sdcard/foo/iris-graft-tok999-2.png'), 'tok999');
  assert.equal(extractTokenFromImageName('content://media/external/images/media/iris-graft-tok555-12.jpeg'), 'tok555');
  assert.equal(extractTokenFromImageName('plain-image.jpg'), null);
});

test('builds token-scoped hint paths', () => {
  assert.equal(buildPendingHintPath('/data/local/tmp/iris-thread-hints', 'tok123'), '/data/local/tmp/iris-thread-hints/pending/tok123.json');
  assert.equal(buildClaimedHintPath('/data/local/tmp/iris-thread-hints', 'tok123'), '/data/local/tmp/iris-thread-hints/claimed/tok123.json');
  assert.equal(buildDoneHintPath('/data/local/tmp/iris-thread-hints', 'tok123'), '/data/local/tmp/iris-thread-hints/done/tok123.json');
});

test('plans pending to claimed hint transition for a token', () => {
  assert.deepEqual(planHintClaim('/data/local/tmp/iris-thread-hints', 'tok123'), {
    token: 'tok123',
    pendingPath: '/data/local/tmp/iris-thread-hints/pending/tok123.json',
    claimedPath: '/data/local/tmp/iris-thread-hints/claimed/tok123.json',
    donePath: '/data/local/tmp/iris-thread-hints/done/tok123.json',
  });
});

test('encodes thread hint json payload', () => {
  const encoded = encodeThreadHint({
    token: 'tok123',
    room: '18476130232878491',
    threadId: '3803466729815130113',
    threadScope: 2,
    createdAt: 1711280000,
  });

  assert.match(encoded, /"token":"tok123"/);
  assert.match(encoded, /"threadId":"3803466729815130113"/);
});
