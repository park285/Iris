import test from 'node:test';
import assert from 'node:assert/strict';
import {
  FingerprintSessionStore,
  fingerprintUriStrings,
  mergeSessionMeta,
} from '../shared/fingerprint-store.js';
import { type SessionMeta } from '../shared/session.js';

const session = (overrides: Partial<SessionMeta> = {}): SessionMeta => ({
  sessionId: 'sess-1',
  threadId: '3804041011037167620',
  threadScope: 2,
  roomId: '18478615493603057',
  createdAt: Date.now(),
  ...overrides,
});

test('fingerprintUriStrings returns "empty" for empty array', () => {
  assert.equal(fingerprintUriStrings([]), 'empty');
});

test('fingerprintUriStrings joins count and URIs', () => {
  assert.equal(
    fingerprintUriStrings(['content://a', 'content://b']),
    '2:content://a|content://b',
  );
});

test('store.get returns null for unknown fingerprint', () => {
  const store = new FingerprintSessionStore();
  assert.equal(store.get('unknown'), null);
});

test('store round-trips a session by fingerprint', () => {
  const store = new FingerprintSessionStore();
  const s = session();
  store.set('fp1', s);
  assert.deepEqual(store.get('fp1'), s);
});

test('store evicts entries beyond TTL', () => {
  const store = new FingerprintSessionStore(100);
  store.set('fp1', session({ createdAt: Date.now() - 200 }));
  assert.equal(store.get('fp1'), null);
});

test('store evicts oldest entries beyond maxSize', () => {
  const store = new FingerprintSessionStore(60_000, 2);
  store.set('fp1', session({ sessionId: 's1' }));
  store.set('fp2', session({ sessionId: 's2' }));
  store.set('fp3', session({ sessionId: 's3' }));
  assert.equal(store.get('fp1'), null);
  assert.notEqual(store.get('fp3'), null);
});

test('mergeSessionMeta prefers previous sessionId, fills missing threadId from next', () => {
  const prev = session({ threadId: null });
  const next = session({ sessionId: 'other', threadId: '999' });
  const merged = mergeSessionMeta(prev, next)!;
  assert.equal(merged.sessionId, prev.sessionId);
  assert.equal(merged.threadId, '999');
});

test('mergeSessionMeta does not downgrade threadScope', () => {
  const prev = session({ threadScope: 2 });
  const next = session({ threadScope: 0 });
  const merged = mergeSessionMeta(prev, next)!;
  assert.equal(merged.threadScope, 2);
});

test('store.set merges with existing session for same fingerprint', () => {
  const store = new FingerprintSessionStore();
  store.set('fp1', session({ threadId: null, threadScope: 0 }));
  store.set('fp1', session({ sessionId: 'other', threadId: '999', threadScope: 2 }));
  const result = store.get('fp1')!;
  assert.equal(result.threadId, '999');
  assert.equal(result.threadScope, 2);
});
