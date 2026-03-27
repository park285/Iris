import test from 'node:test';
import assert from 'node:assert/strict';

import { KAKAO_IMAGE_GRAFT_TARGET } from '../shared/kakao.js';
import { installThreadImageDiscovery } from '../thread-image-discovery.js';

function createRuntime() {
  const sent: string[] = [];
  const emptyOverloads = () => ({ overloads: [] as Array<{ implementation?: (...args: unknown[]) => unknown }> });
  const classes = new Map<string, unknown>([
    ['com.kakao.talk.activity.f', { b6: emptyOverloads(), Z5: emptyOverloads() }],
    ['com.kakao.talk.activity.TaskRootActivity', { c5: emptyOverloads() }],
    ['com.kakao.talk.activity.chatroom.ChatRoomHolderActivity', { onCreate: emptyOverloads(), onNewIntent: emptyOverloads() }],
    ['com.kakao.talk.activity.chatroom.c', { cf: emptyOverloads() }],
    ['Fp.N', { $init: emptyOverloads(), h: emptyOverloads() }],
    [
      'bh.c',
      {
        o: emptyOverloads(),
        p: emptyOverloads(),
        u: emptyOverloads(),
        t: emptyOverloads(),
        A: emptyOverloads(),
        B: emptyOverloads(),
        class: {
          getDeclaredMethods() {
            return [];
          },
        },
      },
    ],
    [KAKAO_IMAGE_GRAFT_TARGET.className, { [KAKAO_IMAGE_GRAFT_TARGET.methodName]: emptyOverloads() }],
    ['java.lang.Long', {}],
    ['java.lang.String', {}],
    ['java.lang.System', {}],
  ]);

  return {
    sent,
    runtime: {
      send(message: string) {
        sent.push(message);
      },
      Java: {
        perform(callback: () => void) {
          callback();
        },
        use(className: string) {
          const klass = classes.get(className);
          if (klass == null) {
            throw new Error(`missing fake class: ${className}`);
          }

          return klass;
        },
      },
    },
  };
}

test('installThreadImageDiscovery emits an install banner', () => {
  const { runtime, sent } = createRuntime();

  assert.equal(installThreadImageDiscovery(runtime), true);
  assert.ok(sent.includes('[discovery] thread-image-discovery hooks installed'));
});
