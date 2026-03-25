import test from 'node:test';
import assert from 'node:assert/strict';

import {
  BASE_INTENT_FILTER_TARGET,
  CHAT_MEDIA_SENDER_TARGET,
  KAKAO_IMAGE_GRAFT_TARGET,
} from '../shared/kakao.js';

type FakeOverload = {
  implementation?: ((...args: any[]) => unknown) | null;
  argumentTypes?: Array<{ className: string }>;
  apply: (...args: any[]) => unknown;
};

function createFakeOverload(argumentTypeNames: string[] = []): FakeOverload {
  return {
    implementation: null,
    argumentTypes: argumentTypeNames.map((className) => ({ className })),
    apply() {
      return undefined;
    },
  };
}

function createFakeJavaClass(
  methodNames: string[],
  options: {
    argumentTypesByMethod?: Record<string, string[]>;
    fieldNames?: string[];
  } = {},
): Record<string, any> {
  const klass: Record<string, any> = {};
  for (const methodName of methodNames) {
    klass[methodName] = {
      overloads: [createFakeOverload(options.argumentTypesByMethod?.[methodName] ?? [])],
    };
  }
  const declaredFields = new Set(options.fieldNames ?? []);
  klass.class = {
    getDeclaredField(fieldName: string) {
      if (!declaredFields.has(fieldName)) {
        throw new Error(`missing fake field: ${fieldName}`);
      }
      return {
        getName() {
          return fieldName;
        },
      };
    },
  };
  return klass;
}

function createBlockedRuntime() {
  const sent: string[] = [];
  const sendingLogClassName = 'com.kakao.talk.db.model.chatlog.ChatSendingLog';
  const classes = new Map<string, Record<string, { overloads: FakeOverload[] }>>([
    [BASE_INTENT_FILTER_TARGET.className, createFakeJavaClass([BASE_INTENT_FILTER_TARGET.captureMethod])],
    [
      CHAT_MEDIA_SENDER_TARGET.className,
      createFakeJavaClass(
        [
          CHAT_MEDIA_SENDER_TARGET.entryMethod,
          CHAT_MEDIA_SENDER_TARGET.processMethod,
          CHAT_MEDIA_SENDER_TARGET.injectMethod,
        ],
        {
          argumentTypesByMethod: {
            [CHAT_MEDIA_SENDER_TARGET.injectMethod]: [sendingLogClassName],
          },
        },
      ),
    ],
    [
      KAKAO_IMAGE_GRAFT_TARGET.className,
      createFakeJavaClass([KAKAO_IMAGE_GRAFT_TARGET.methodName], {
        argumentTypesByMethod: {
          [KAKAO_IMAGE_GRAFT_TARGET.methodName]: ['java.lang.Object', sendingLogClassName],
        },
      }),
    ],
    [
      sendingLogClassName,
      createFakeJavaClass([], {
        fieldNames: [KAKAO_IMAGE_GRAFT_TARGET.attachmentField, KAKAO_IMAGE_GRAFT_TARGET.threadScopeField],
      }),
    ],
  ]);

  const runtime = {
    Java: {
      available: true,
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
    send(message: string) {
      sent.push(message);
    },
  };

  return { runtime, sent };
}

test('assessThreadImageHookSpec blocks readiness when a required hook target is missing', async () => {
  const graft = await import('../thread-image-graft.js');

  assert.equal(typeof graft.assessThreadImageHookSpec, 'function');

  const assessment = graft.assessThreadImageHookSpec(
    {
      methodsByClass: {
        [BASE_INTENT_FILTER_TARGET.className]: [BASE_INTENT_FILTER_TARGET.captureMethod],
        [CHAT_MEDIA_SENDER_TARGET.className]: [
          CHAT_MEDIA_SENDER_TARGET.entryMethod,
          CHAT_MEDIA_SENDER_TARGET.processMethod,
        ],
        [KAKAO_IMAGE_GRAFT_TARGET.className]: [KAKAO_IMAGE_GRAFT_TARGET.methodName],
      },
      fieldsByClass: {},
      sendingLogClassName: null,
    },
    1711363200000,
  );

  assert.equal(assessment.ready, false);
  assert.equal(assessment.state, 'BLOCKED');
  assert.equal(assessment.hooks.b6.installed, false);
  assert.equal(assessment.hooks.o.installed, false);
  assert.equal(assessment.hooks.t.installed, false);
  assert.equal(assessment.hooks.A.installed, false);
  assert.equal(assessment.hooks.u.installed, false);
  assert.match(assessment.hooks.A.missingReason ?? '', /missing method/i);
});

test('assessThreadImageHookSpec blocks readiness when required sending-log fields are missing', async () => {
  const graft = await import('../thread-image-graft.js');

  const sendingLogClassName = 'com.kakao.talk.db.model.chatlog.ChatSendingLog';
  const assessment = graft.assessThreadImageHookSpec(
    {
      methodsByClass: {
        [BASE_INTENT_FILTER_TARGET.className]: [BASE_INTENT_FILTER_TARGET.captureMethod],
        [CHAT_MEDIA_SENDER_TARGET.className]: [
          CHAT_MEDIA_SENDER_TARGET.entryMethod,
          CHAT_MEDIA_SENDER_TARGET.processMethod,
          CHAT_MEDIA_SENDER_TARGET.injectMethod,
        ],
        [KAKAO_IMAGE_GRAFT_TARGET.className]: [KAKAO_IMAGE_GRAFT_TARGET.methodName],
      },
      fieldsByClass: {
        [sendingLogClassName]: [
          KAKAO_IMAGE_GRAFT_TARGET.attachmentField,
          KAKAO_IMAGE_GRAFT_TARGET.threadScopeField,
        ],
      },
      sendingLogClassName,
    },
    1711363200000,
  );

  assert.equal(assessment.ready, false);
  assert.equal(assessment.state, 'BLOCKED');
  assert.match(assessment.hooks.A.missingReason ?? '', /threadId field/i);
  assert.match(assessment.hooks.u.missingReason ?? '', /threadId field/i);
});

test('snapshotThreadImageGraftHealth includes per-hook status counters and last-seen timestamps', async () => {
  const graft = await import('../thread-image-graft.js');

  assert.equal(typeof graft.createThreadImageGraftHealth, 'function');
  assert.equal(typeof graft.markThreadImageHookInstalled, 'function');
  assert.equal(typeof graft.setThreadImageGraftState, 'function');
  assert.equal(typeof graft.recordThreadImageEvent, 'function');
  assert.equal(typeof graft.snapshotThreadImageGraftHealth, 'function');

  const health = graft.createThreadImageGraftHealth(1711363200000);
  for (const hook of ['b6', 'o', 't', 'A', 'u'] as const) {
    graft.markThreadImageHookInstalled(health, hook, 1711363200100);
  }
  graft.setThreadImageGraftState(health, 'READY', 1711363200200);
  graft.recordThreadImageEvent(health, 'capture', 'success', 1711363200300);
  graft.recordThreadImageEvent(health, 'restore', 'skip', 1711363200400);
  graft.recordThreadImageEvent(health, 'inject', 'failure', 1711363200500);

  const payload = graft.snapshotThreadImageGraftHealth(health, 'heartbeat', 1711363200600);

  assert.equal(payload.type, 'graft-health');
  assert.equal(payload.event, 'heartbeat');
  assert.equal(payload.state, 'DEGRADED');
  assert.equal(payload.ready, false);
  assert.equal(payload.timestampMs, 1711363200600);
  assert.equal(payload.hooks.b6.installed, true);
  assert.equal(payload.hooks.o.installed, true);
  assert.equal(payload.hooks.t.installed, true);
  assert.equal(payload.hooks.A.installed, true);
  assert.equal(payload.hooks.u.installed, true);
  assert.deepEqual(payload.counters, {
    capture: { success: 1, skip: 0, failure: 0 },
    restore: { success: 0, skip: 1, failure: 0 },
    inject: { success: 0, skip: 0, failure: 1 },
  });
  assert.deepEqual(payload.lastSeenMs, {
    capture: 1711363200300,
    restore: 1711363200400,
    inject: 1711363200500,
  });
});

test('installThreadImageGraft emits JSON string health payloads and stays blocked when the field gate fails', async () => {
  const graft = await import('../thread-image-graft.js');
  const { runtime, sent } = createBlockedRuntime();

  graft.installThreadImageGraft(runtime);

  const structuredMessages = sent
    .filter((message) => message.startsWith('{'))
    .map((message) => JSON.parse(message));

  assert.ok(structuredMessages.length >= 2);
  assert.equal(structuredMessages[0].type, 'graft-health');
  assert.equal(structuredMessages.at(-1)?.event, 'heartbeat');
  assert.equal(structuredMessages.at(-1)?.state, 'BLOCKED');
  assert.equal(structuredMessages.at(-1)?.ready, false);
  assert.equal(structuredMessages.at(-1)?.hooks.A.installed, false);
  assert.equal(structuredMessages.at(-1)?.hooks.b6.installed, false);
  assert.match(structuredMessages.at(-1)?.hooks.A.missingReason ?? '', /threadId field/i);
  assert.ok(sent.every((message) => typeof message === 'string'));
});

test('installThreadImageGraft schedules a periodic heartbeat timer', async () => {
  const graft = await import('../thread-image-graft.js');
  const { runtime } = createBlockedRuntime();

  const originalSetInterval = globalThis.setInterval;
  const originalClearInterval = globalThis.clearInterval;
  let scheduledCount = 0;
  let scheduledDelay = 0;

  globalThis.setInterval = ((_: () => void, delay?: number) => {
    scheduledCount += 1;
    scheduledDelay = Number(delay ?? 0);
    return { unref() {} } as unknown as ReturnType<typeof setInterval>;
  }) as typeof setInterval;
  globalThis.clearInterval = (() => undefined) as typeof clearInterval;

  try {
    graft.installThreadImageGraft(runtime);
  } finally {
    globalThis.setInterval = originalSetInterval;
    globalThis.clearInterval = originalClearInterval;
  }

  assert.equal(scheduledCount, 1);
  assert.equal(scheduledDelay, 10_000);
});
