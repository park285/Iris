import test from 'node:test';
import assert from 'node:assert/strict';

test('removes callingPkg from attachment json payloads', async () => {
  const message = await import('../shared/message.js')
    .catch((error) => assert.fail(`expected shared/message.js to load: ${String(error)}`));

  assert.equal(
    message.removeCallingPkgFromAttachment('{"url":"file:///tmp/iris-graft-tok123-0.jpg","callingPkg":"party.qwer.iris"}'),
    '{"url":"file:///tmp/iris-graft-tok123-0.jpg"}',
  );
});

test('keeps attachment payload unchanged when callingPkg is absent or payload is invalid', async () => {
  const message = await import('../shared/message.js')
    .catch((error) => assert.fail(`expected shared/message.js to load: ${String(error)}`));

  assert.equal(
    message.removeCallingPkgFromAttachment('{"url":"file:///tmp/iris-graft-tok123-0.jpg"}'),
    '{"url":"file:///tmp/iris-graft-tok123-0.jpg"}',
  );
  assert.equal(message.removeCallingPkgFromAttachment('not-json'), 'not-json');
  assert.equal(
    message.removeCallingPkgFromAttachment('{"url":"file:///tmp/no-token.jpg"}'),
    '{"url":"file:///tmp/no-token.jpg"}',
  );
});

test('re-checks room ids against the claimed hint', async () => {
  const message = await import('../shared/message.js')
    .catch((error) => assert.fail(`expected shared/message.js to load: ${String(error)}`));

  assert.equal(message.roomMatchesThreadHint('18476130232878491', '18476130232878491'), true);
  assert.equal(message.roomMatchesThreadHint('18476130232878491', 'different-room'), false);
});

test('extracts tokenized path candidates from attachment payloads', async () => {
  const message = await import('../shared/message.js')
    .catch((error) => assert.fail(`expected shared/message.js to load: ${String(error)}`));

  assert.deepEqual(
    message.extractPathCandidatesFromAttachment(
      '{"url":"file:///tmp/iris-graft-tok123-0.jpg","nested":{"originalPath":"/sdcard/iris-graft-tok123-1.png"}}',
    ),
    ['file:///tmp/iris-graft-tok123-0.jpg', '/sdcard/iris-graft-tok123-1.png'],
  );
  assert.deepEqual(message.extractPathCandidatesFromAttachment('{"url":"file:///tmp/plain.jpg"}'), []);
});

test('extracts tokenized path candidates from deeply nested attachment payloads without overflowing the stack', async () => {
  const message = await import('../shared/message.js')
    .catch((error) => assert.fail(`expected shared/message.js to load: ${String(error)}`));

  let attachmentText = '"file:///tmp/iris-graft-deep.jpg"';
  for (let depth = 0; depth < 6_000; depth += 1) {
    attachmentText = `{"nested":${attachmentText}}`;
  }

  assert.deepEqual(message.extractPathCandidatesFromAttachment(attachmentText), ['file:///tmp/iris-graft-deep.jpg']);
});
