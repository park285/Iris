import test from 'node:test';
import assert from 'node:assert/strict';

import {
  IRIS_EXTRAS,
  buildSendingLogDedupeKey,
  copySessionExtras,
  readSessionMeta,
  sessionIdFromIdentifier,
} from '../shared/session.js';

test('reads session id from identifier fallback when extras are missing', () => {
  assert.equal(sessionIdFromIdentifier('iris:session-123'), 'session-123');
  assert.equal(sessionIdFromIdentifier('session-123'), null);
  assert.equal(sessionIdFromIdentifier(null), null);
});

test('reads session metadata from Iris extras', () => {
  const meta = readSessionMeta({
    identifier: null,
    extras: {
      [IRIS_EXTRAS.SESSION_ID]: 'session-123',
      [IRIS_EXTRAS.THREAD_ID]: '3804005398980288513',
      [IRIS_EXTRAS.THREAD_SCOPE]: '2',
      [IRIS_EXTRAS.ROOM_ID]: '18219201472247343',
      [IRIS_EXTRAS.CREATED_AT]: '1711280000',
    },
  });

  assert.deepEqual(meta, {
    sessionId: 'session-123',
    threadId: '3804005398980288513',
    threadScope: 2,
    roomId: '18219201472247343',
    createdAt: 1711280000,
  });
});

test('copies only Iris session extras into a new extras object', () => {
  const copied = copySessionExtras(
    {
      identifier: 'iris:session-123',
      extras: {
        [IRIS_EXTRAS.THREAD_ID]: '3804005398980288513',
        [IRIS_EXTRAS.THREAD_SCOPE]: 2,
        [IRIS_EXTRAS.ROOM_ID]: '18219201472247343',
      },
    },
    {
      existing: 'value',
      unrelated: 1,
    },
  );

  assert.deepEqual(copied, {
    existing: 'value',
    unrelated: 1,
    [IRIS_EXTRAS.SESSION_ID]: 'session-123',
    [IRIS_EXTRAS.THREAD_ID]: '3804005398980288513',
    [IRIS_EXTRAS.THREAD_SCOPE]: 2,
    [IRIS_EXTRAS.ROOM_ID]: '18219201472247343',
    [IRIS_EXTRAS.CREATED_AT]: copied[IRIS_EXTRAS.CREATED_AT],
  });
});

test('prefers client message id for sending log dedupe keys', () => {
  assert.equal(
    buildSendingLogDedupeKey({
      clientMessageId: '987654321',
      identityHash: 44,
      roomId: '18219201472247343',
      messageType: 'Photo',
    }),
    'client:987654321',
  );
});

test('falls back to identity hash for sending log dedupe keys', () => {
  assert.equal(
    buildSendingLogDedupeKey({
      clientMessageId: 0,
      identityHash: 44,
      roomId: '18219201472247343',
      messageType: 'Photo',
    }),
    'identity:18219201472247343:Photo:44',
  );

  assert.equal(
    buildSendingLogDedupeKey({
      clientMessageId: null,
      identityHash: null,
      roomId: '18219201472247343',
      messageType: 'Photo',
    }),
    null,
  );
});
