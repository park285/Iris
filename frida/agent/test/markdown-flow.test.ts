import test from 'node:test';
import assert from 'node:assert/strict';

import { KAKAO_MARKDOWN_GRAFT_TARGET } from '../shared/kakao.js';
import {
  DEFAULT_MARKDOWN_GRAFT_MARKER,
  getThreadMarkdownGraftEntrypoint,
  planMarkdownInjection,
} from '../thread-markdown-graft.js';

test('markdown planner skips when marker is absent', () => {
  const plan = planMarkdownInjection('plain text', DEFAULT_MARKDOWN_GRAFT_MARKER, '3803466729815130113', 2);

  assert.equal(plan.status, 'skip-no-marker');
  assert.equal(plan.injection, null);
});

test('markdown planner injects when marker is present', () => {
  const plan = planMarkdownInjection(`hello ${DEFAULT_MARKDOWN_GRAFT_MARKER}`, DEFAULT_MARKDOWN_GRAFT_MARKER, '3803466729815130113', 2);

  assert.equal(plan.status, 'inject');
  assert.deepEqual(plan.injection, {
    threadId: '3803466729815130113',
    threadScope: 2,
  });
});

test('markdown entrypoint exposes stable marker and target', () => {
  const entrypoint = getThreadMarkdownGraftEntrypoint();
  assert.equal(entrypoint.marker, DEFAULT_MARKDOWN_GRAFT_MARKER);
  assert.deepEqual(entrypoint.target, KAKAO_MARKDOWN_GRAFT_TARGET);
  assert.equal(typeof entrypoint.planMarkdownInjection, 'function');
});
