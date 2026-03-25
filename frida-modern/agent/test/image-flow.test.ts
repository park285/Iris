import test from 'node:test';
import assert from 'node:assert/strict';
import type { SessionMeta } from '../shared/session.js';

const session = (overrides: Partial<SessionMeta> = {}): SessionMeta => ({
  sessionId: 'sess-1',
  threadId: '3804041011037167620',
  threadScope: 2,
  roomId: '18478615493603057',
  createdAt: Date.now(),
  ...overrides,
});

test('planImageGraft skips when session is null', async () => {
  const { planImageGraft } = await import('../thread-image-graft.js');
  const plan = planImageGraft(null, '18478615493603057');
  assert.equal(plan.status, 'skip-no-session');
  assert.equal(plan.injection, null);
});

test('planImageGraft skips when session has no threadId', async () => {
  const { planImageGraft } = await import('../thread-image-graft.js');
  const plan = planImageGraft(session({ threadId: null, threadScope: 0 }), '18478615493603057');
  assert.equal(plan.status, 'skip-no-thread');
  assert.equal(plan.injection, null);
});

test('planImageGraft skips when threadScope less than 2', async () => {
  const { planImageGraft } = await import('../thread-image-graft.js');
  const plan = planImageGraft(session({ threadScope: 1 }), '18478615493603057');
  assert.equal(plan.status, 'skip-no-thread');
  assert.equal(plan.injection, null);
});

test('planImageGraft skips when roomId does not match', async () => {
  const { planImageGraft } = await import('../thread-image-graft.js');
  const plan = planImageGraft(session(), 'different-room');
  assert.equal(plan.status, 'skip-room-mismatch');
  assert.equal(plan.injection, null);
});

test('planImageGraft skips when session roomId is null (fail-closed)', async () => {
  const { planImageGraft } = await import('../thread-image-graft.js');
  const plan = planImageGraft(session({ roomId: null }), '18478615493603057');
  assert.equal(plan.status, 'skip-room-mismatch');
  assert.equal(plan.injection, null);
});

test('planImageGraft injects when session and room match with scope >= 2', async () => {
  const { planImageGraft } = await import('../thread-image-graft.js');
  const s = session();
  const plan = planImageGraft(s, s.roomId!);
  assert.equal(plan.status, 'inject');
  assert.deepEqual(plan.injection, {
    threadId: s.threadId,
    threadScope: s.threadScope,
  });
});
