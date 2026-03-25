import test from 'node:test';
import assert from 'node:assert/strict';

import { DEFAULT_MARKDOWN_GRAFT_MARKER, planMarkdownInjection } from '../thread-markdown-graft.js';

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
