import {
  BASE_INTENT_FILTER_TARGET,
  CHAT_MEDIA_SENDER_TARGET,
  KAKAO_IMAGE_GRAFT_TARGET,
  THREAD_IMAGE_GRAFT_HOOK_SPECS,
  type ThreadImageGraftHookKey,
} from './shared/kakao.js';
import {
  copySessionExtras,
  readSessionMeta,
  type IntentSnapshot,
  type SessionMeta,
  IRIS_EXTRAS,
} from './shared/session.js';
import { FingerprintSessionStore, fingerprintUriStrings, mergeSessionMeta } from './shared/fingerprint-store.js';
import { isPhotoMessageType, removeCallingPkgFromAttachment } from './shared/message.js';

// 모듈 수준 상태: V8/Frida 단일 스레드이므로 동기 체인(o→t→A) 내에서 안전
const fingerprintStore = new FingerprintSessionStore();
let currentMediaSenderSession: SessionMeta | null = null;
let threadImageGraftHeartbeatTimer: ReturnType<typeof setInterval> | null = null;

const hasNativePointer =
  typeof (globalThis as typeof globalThis & { NativePointer?: unknown }).NativePointer !== 'undefined';
const JavaBridgeGlobal = hasNativePointer
  ? (((await import('frida-java-bridge')).default as unknown) as RuntimeLike['Java'])
  : null;

type RuntimeLike = {
  Java?: {
    available?: boolean;
    perform: (callback: () => void) => void;
    use: (className: string) => any;
  };
  send?: (message: string) => void;
  now?: () => number;
};

// ─── 결과 타입 ───────────────────────────────────────────────────────────────

export type ImageGraftPlan = {
  status: 'skip-no-session' | 'skip-no-thread' | 'skip-room-mismatch' | 'inject';
  injection: {
    threadId: string;
    threadScope: number;
  } | null;
};

const THREAD_IMAGE_GRAFT_EVENTS = ['capture', 'restore', 'inject'] as const;
type ThreadImageGraftEvent = (typeof THREAD_IMAGE_GRAFT_EVENTS)[number];

const THREAD_IMAGE_GRAFT_OUTCOMES = ['success', 'skip', 'failure'] as const;
type ThreadImageGraftOutcome = (typeof THREAD_IMAGE_GRAFT_OUTCOMES)[number];

export type ThreadImageGraftState =
  | 'BOOTING'
  | 'HOOKING'
  | 'WARM'
  | 'READY'
  | 'DEGRADED'
  | 'BLOCKED';

export type ThreadImageGraftHookStatus = {
  installed: boolean;
  className: string;
  methodName: string;
  installedAtMs: number | null;
  lastSeenAtMs: number | null;
  missingReason: string | null;
};

export type ThreadImageGraftCounters = Record<
  ThreadImageGraftEvent,
  Record<ThreadImageGraftOutcome, number>
>;

export type ThreadImageGraftLastSeen = Record<ThreadImageGraftEvent, number | null>;

export type ThreadImageGraftHealth = {
  state: ThreadImageGraftState;
  ready: boolean;
  hooks: Record<ThreadImageGraftHookKey, ThreadImageGraftHookStatus>;
  counters: ThreadImageGraftCounters;
  lastSeenMs: ThreadImageGraftLastSeen;
  updatedAtMs: number;
};

export type ThreadImageHookSpecAssessmentInput = {
  methodsByClass: Record<string, readonly string[]>;
  fieldsByClass: Record<string, readonly string[]>;
  sendingLogClassName: string | null;
};

export type ThreadImageHookSpecAssessment = Pick<ThreadImageGraftHealth, 'state' | 'ready' | 'hooks'> & {
  missingRequirements: string[];
  sendingLogClassName: string | null;
};

export type ThreadImageGraftHealthPayload = {
  type: 'graft-health';
  event: string;
  state: ThreadImageGraftState;
  ready: boolean;
  hooks: Record<ThreadImageGraftHookKey, ThreadImageGraftHookStatus>;
  counters: ThreadImageGraftCounters;
  lastSeenMs: ThreadImageGraftLastSeen;
  timestampMs: number;
};

const THREAD_IMAGE_GRAFT_HEARTBEAT_INTERVAL_MS = 10_000;

function nowMs(runtime?: RuntimeLike): number {
  if (runtime != null && typeof runtime.now === 'function') {
    return runtime.now();
  }
  return Date.now();
}

function resolveJavaBridge(runtime: RuntimeLike): RuntimeLike['Java'] | null {
  return runtime.Java ?? JavaBridgeGlobal;
}

function buildEmptyCounters(): ThreadImageGraftCounters {
  return {
    capture: { success: 0, skip: 0, failure: 0 },
    restore: { success: 0, skip: 0, failure: 0 },
    inject: { success: 0, skip: 0, failure: 0 },
  };
}

function buildEmptyLastSeen(): ThreadImageGraftLastSeen {
  return {
    capture: null,
    restore: null,
    inject: null,
  };
}

function buildThreadImageHookStatuses(): Record<ThreadImageGraftHookKey, ThreadImageGraftHookStatus> {
  const hooks = {} as Record<ThreadImageGraftHookKey, ThreadImageGraftHookStatus>;

  for (const [hook, spec] of Object.entries(THREAD_IMAGE_GRAFT_HOOK_SPECS) as Array<
    [ThreadImageGraftHookKey, (typeof THREAD_IMAGE_GRAFT_HOOK_SPECS)[ThreadImageGraftHookKey]]
  >) {
    hooks[hook] = {
      installed: false,
      className: spec.className,
      methodName: spec.methodName,
      installedAtMs: null,
      lastSeenAtMs: null,
      missingReason: null,
    };
  }

  return hooks;
}

function cloneThreadImageHooks(
  hooks: Record<ThreadImageGraftHookKey, ThreadImageGraftHookStatus>,
): Record<ThreadImageGraftHookKey, ThreadImageGraftHookStatus> {
  const copy = {} as Record<ThreadImageGraftHookKey, ThreadImageGraftHookStatus>;

  for (const hook of Object.keys(hooks) as ThreadImageGraftHookKey[]) {
    copy[hook] = { ...hooks[hook] };
  }

  return copy;
}

function cloneThreadImageCounters(counters: ThreadImageGraftCounters): ThreadImageGraftCounters {
  return {
    capture: { ...counters.capture },
    restore: { ...counters.restore },
    inject: { ...counters.inject },
  };
}

export function createThreadImageGraftHealth(timestampMs = Date.now()): ThreadImageGraftHealth {
  return {
    state: 'BOOTING',
    ready: false,
    hooks: buildThreadImageHookStatuses(),
    counters: buildEmptyCounters(),
    lastSeenMs: buildEmptyLastSeen(),
    updatedAtMs: timestampMs,
  };
}

export function setThreadImageGraftState(
  health: ThreadImageGraftHealth,
  state: ThreadImageGraftState,
  timestampMs = Date.now(),
  ready = state === 'READY',
): ThreadImageGraftHealth {
  health.state = state;
  health.ready = ready;
  health.updatedAtMs = timestampMs;
  return health;
}

function setThreadImageHookMissingReason(
  health: ThreadImageGraftHealth,
  hook: ThreadImageGraftHookKey,
  missingReason: string,
): void {
  const existing = health.hooks[hook].missingReason;
  health.hooks[hook].missingReason = existing == null ? missingReason : `${existing}; ${missingReason}`;
}

function touchThreadImageHook(
  health: ThreadImageGraftHealth,
  hook: ThreadImageGraftHookKey,
  timestampMs = Date.now(),
): void {
  health.hooks[hook].lastSeenAtMs = timestampMs;
  health.updatedAtMs = timestampMs;
}

export function markThreadImageHookInstalled(
  health: ThreadImageGraftHealth,
  hook: ThreadImageGraftHookKey,
  timestampMs = Date.now(),
): ThreadImageGraftHealth {
  health.hooks[hook].installed = true;
  health.hooks[hook].installedAtMs ??= timestampMs;
  health.hooks[hook].missingReason = null;
  touchThreadImageHook(health, hook, timestampMs);
  return health;
}

export function recordThreadImageEvent(
  health: ThreadImageGraftHealth,
  event: ThreadImageGraftEvent,
  outcome: ThreadImageGraftOutcome,
  timestampMs = Date.now(),
  touchedHooks: ThreadImageGraftHookKey[] = [],
): ThreadImageGraftHealth {
  health.counters[event][outcome] += 1;
  health.lastSeenMs[event] = timestampMs;
  health.updatedAtMs = timestampMs;

  for (const hook of touchedHooks) {
    touchThreadImageHook(health, hook, timestampMs);
  }

  if (outcome === 'failure' && health.state !== 'BLOCKED') {
    setThreadImageGraftState(health, 'DEGRADED', timestampMs, false);
  }

  return health;
}

export function snapshotThreadImageGraftHealth(
  health: ThreadImageGraftHealth,
  event: string,
  timestampMs = Date.now(),
): ThreadImageGraftHealthPayload {
  return {
    type: 'graft-health',
    event,
    state: health.state,
    ready: health.ready,
    hooks: cloneThreadImageHooks(health.hooks),
    counters: cloneThreadImageCounters(health.counters),
    lastSeenMs: { ...health.lastSeenMs },
    timestampMs,
  };
}

export function assessThreadImageHookSpec(
  input: ThreadImageHookSpecAssessmentInput,
  timestampMs = Date.now(),
): ThreadImageHookSpecAssessment {
  const health = createThreadImageGraftHealth(timestampMs);
  setThreadImageGraftState(health, 'HOOKING', timestampMs, false);
  const missingRequirements: string[] = [];

  for (const [hook, spec] of Object.entries(THREAD_IMAGE_GRAFT_HOOK_SPECS) as Array<
    [ThreadImageGraftHookKey, (typeof THREAD_IMAGE_GRAFT_HOOK_SPECS)[ThreadImageGraftHookKey]]
  >) {
    const availableMethods = new Set(input.methodsByClass[spec.className] ?? []);
    if (!availableMethods.has(spec.methodName)) {
      const reason = `missing method ${spec.className}.${spec.methodName}`;
      setThreadImageHookMissingReason(health, hook, reason);
      missingRequirements.push(reason);
    }
  }

  const missingFieldReasons: string[] = [];
  if (input.sendingLogClassName == null) {
    missingFieldReasons.push('missing sendingLog class for field gate');
  } else {
    const availableFields = new Set(input.fieldsByClass[input.sendingLogClassName] ?? []);

    if (!availableFields.has(KAKAO_IMAGE_GRAFT_TARGET.attachmentField)) {
      missingFieldReasons.push(`missing attachment field ${KAKAO_IMAGE_GRAFT_TARGET.attachmentField}`);
    }
    if (!availableFields.has(KAKAO_IMAGE_GRAFT_TARGET.threadScopeField)) {
      missingFieldReasons.push(`missing threadScope field ${KAKAO_IMAGE_GRAFT_TARGET.threadScopeField}`);
    }
    if (!availableFields.has(KAKAO_IMAGE_GRAFT_TARGET.threadIdField)) {
      missingFieldReasons.push(`missing threadId field ${KAKAO_IMAGE_GRAFT_TARGET.threadIdField}`);
    }
  }

  if (missingFieldReasons.length > 0) {
    const combinedReason = missingFieldReasons.join('; ');
    setThreadImageHookMissingReason(health, 'A', combinedReason);
    setThreadImageHookMissingReason(health, 'u', combinedReason);
    missingRequirements.push(...missingFieldReasons);
  }

  if (missingRequirements.length > 0) {
    setThreadImageGraftState(health, 'BLOCKED', timestampMs, false);
  }

  return {
    state: health.state,
    ready: health.ready,
    hooks: cloneThreadImageHooks(health.hooks),
    missingRequirements,
    sendingLogClassName: input.sendingLogClassName,
  };
}

function emitThreadImageGraftHealth(
  runtime: RuntimeLike,
  health: ThreadImageGraftHealth,
  event: string,
  timestampMs = nowMs(runtime),
): void {
  runtime.send?.(JSON.stringify(snapshotThreadImageGraftHealth(health, event, timestampMs)));
}

function stopThreadImageGraftHeartbeat(): void {
  if (threadImageGraftHeartbeatTimer == null) {
    return;
  }
  clearInterval(threadImageGraftHeartbeatTimer);
  threadImageGraftHeartbeatTimer = null;
}

function startThreadImageGraftHeartbeat(
  runtime: RuntimeLike,
  health: ThreadImageGraftHealth,
): void {
  stopThreadImageGraftHeartbeat();
  if (typeof globalThis.setInterval !== 'function') {
    return;
  }
  threadImageGraftHeartbeatTimer = setInterval(() => {
    emitThreadImageGraftHealth(runtime, health, 'heartbeat');
  }, THREAD_IMAGE_GRAFT_HEARTBEAT_INTERVAL_MS);
  (threadImageGraftHeartbeatTimer as { unref?: () => void }).unref?.();
}

// ─── 순수 결정 함수 (테스트 가능) ──────────────────────────────────────────

export function planImageGraft(session: SessionMeta | null, roomId: string): ImageGraftPlan {
  if (session == null) {
    return { status: 'skip-no-session', injection: null };
  }

  if (session.threadId == null || session.threadScope < 2) {
    return { status: 'skip-no-thread', injection: null };
  }

  // roomId가 null이면 fail-closed
  if (session.roomId == null || session.roomId !== roomId) {
    return { status: 'skip-room-mismatch', injection: null };
  }

  return {
    status: 'inject',
    injection: {
      threadId: session.threadId,
      threadScope: session.threadScope,
    },
  };
}

// ─── 로그 헬퍼 ──────────────────────────────────────────────────────────────

function log(runtime: RuntimeLike, message: string): void {
  runtime.send?.(`[graft] ${message}`);
}

function resolveAvailableMethodTargets(runtime: RuntimeLike): Record<string, string[]> {
  const methodsByClass: Record<string, string[]> = {};
  const JavaBridge = resolveJavaBridge(runtime);
  if (JavaBridge == null) {
    return methodsByClass;
  }

  const specsByClass = new Map<string, Set<string>>();
  for (const spec of Object.values(THREAD_IMAGE_GRAFT_HOOK_SPECS)) {
    if (!specsByClass.has(spec.className)) {
      specsByClass.set(spec.className, new Set());
    }
    specsByClass.get(spec.className)!.add(spec.methodName);
  }

  for (const [className, methods] of specsByClass.entries()) {
    try {
      const klass = JavaBridge.use(className);
      methodsByClass[className] = [...methods].filter((methodName) => {
        const target = (klass as Record<string, any>)[methodName];
        return Array.isArray(target?.overloads) && target.overloads.length > 0;
      });
    } catch {
      methodsByClass[className] = [];
    }
  }

  return methodsByClass;
}

function readSendingLogClassNameFromMethod(method: any, argumentIndex: number): string | null {
  try {
    const overload = method?.overloads?.[0];
    const className = overload?.argumentTypes?.[argumentIndex]?.className;
    return typeof className === 'string' && className.length > 0 ? className : null;
  } catch {
    return null;
  }
}

function resolveSendingLogClassName(runtime: RuntimeLike): string | null {
  const JavaBridge = resolveJavaBridge(runtime);
  if (JavaBridge == null) {
    return null;
  }

  try {
    const sender = JavaBridge.use(CHAT_MEDIA_SENDER_TARGET.className);
    const fromInject = readSendingLogClassNameFromMethod(sender?.[CHAT_MEDIA_SENDER_TARGET.injectMethod], 0);
    if (fromInject != null) {
      return fromInject;
    }
  } catch {
    // Ignore and fall back to u()
  }

  try {
    const requestCompanion = JavaBridge.use(KAKAO_IMAGE_GRAFT_TARGET.className);
    return readSendingLogClassNameFromMethod(requestCompanion?.[KAKAO_IMAGE_GRAFT_TARGET.methodName], 1);
  } catch {
    return null;
  }
}

function resolveAvailableFieldTargets(
  runtime: RuntimeLike,
  sendingLogClassName: string | null,
): Record<string, string[]> {
  const fieldsByClass: Record<string, string[]> = {};
  const JavaBridge = resolveJavaBridge(runtime);
  if (JavaBridge == null || sendingLogClassName == null) {
    return fieldsByClass;
  }

  try {
    const sendingLogClass = JavaBridge.use(sendingLogClassName);
    const classHandle = (sendingLogClass as Record<string, any>).class;
    const fieldNames = [
      KAKAO_IMAGE_GRAFT_TARGET.attachmentField,
      KAKAO_IMAGE_GRAFT_TARGET.threadScopeField,
      KAKAO_IMAGE_GRAFT_TARGET.threadIdField,
    ];

    fieldsByClass[sendingLogClassName] = fieldNames.filter((fieldName) => {
      try {
        classHandle?.getDeclaredField(fieldName);
        return true;
      } catch {
        return false;
      }
    });
  } catch {
    fieldsByClass[sendingLogClassName] = [];
  }

  return fieldsByClass;
}

function resolveThreadImageHookAssessment(runtime: RuntimeLike, timestampMs = nowMs(runtime)): ThreadImageHookSpecAssessment {
  const sendingLogClassName = resolveSendingLogClassName(runtime);
  return assessThreadImageHookSpec(
    {
      methodsByClass: resolveAvailableMethodTargets(runtime),
      fieldsByClass: resolveAvailableFieldTargets(runtime, sendingLogClassName),
      sendingLogClassName,
    },
    timestampMs,
  );
}

function missingThreadImageRequirements(health: ThreadImageGraftHealth): string[] {
  return Object.entries(health.hooks)
    .filter(([, status]) => status.missingReason != null)
    .map(([hook, status]) => `${hook}:${status.missingReason}`);
}

function applyThreadImageHookAssessment(
  health: ThreadImageGraftHealth,
  assessment: ThreadImageHookSpecAssessment,
  timestampMs = Date.now(),
): void {
  for (const hook of Object.keys(health.hooks) as ThreadImageGraftHookKey[]) {
    health.hooks[hook] = {
      ...health.hooks[hook],
      ...assessment.hooks[hook],
      installed: false,
      installedAtMs: null,
      lastSeenAtMs: null,
    };
  }
  setThreadImageGraftState(health, assessment.state, timestampMs, assessment.ready);
}

function finalizeThreadImageGraftInstall(
  health: ThreadImageGraftHealth,
  timestampMs = Date.now(),
): void {
  const missingRequirements = missingThreadImageRequirements(health);
  if (missingRequirements.length > 0) {
    setThreadImageGraftState(health, 'BLOCKED', timestampMs, false);
    return;
  }

  const allHooksInstalled = (Object.keys(health.hooks) as ThreadImageGraftHookKey[]).every(
    (hook) => health.hooks[hook].installed,
  );
  setThreadImageGraftState(health, allHooksInstalled ? 'READY' : 'DEGRADED', timestampMs, allHooksInstalled);
}

// ─── 인텐트 헬퍼 ────────────────────────────────────────────────────────────

function readIntentIdentifier(intent: any): string | null {
  if (intent == null) {
    return null;
  }

  try {
    const identifier = intent.getIdentifier();
    return identifier == null ? null : String(identifier);
  } catch {
    return null;
  }
}

function readIntentStringExtra(intent: any, key: string): string | null {
  if (intent == null) {
    return null;
  }

  try {
    if (!intent.hasExtra(key)) {
      return null;
    }
    const value = intent.getStringExtra(key);
    return value == null ? null : String(value);
  } catch {
    return null;
  }
}

function readIntentIntExtra(intent: any, key: string): number | null {
  if (intent == null) {
    return null;
  }

  try {
    if (!intent.hasExtra(key)) {
      return null;
    }
    return Number(intent.getIntExtra(key, 0));
  } catch {
    return null;
  }
}

function readIntentLongExtra(intent: any, key: string): number | null {
  if (intent == null) {
    return null;
  }

  try {
    if (!intent.hasExtra(key)) {
      return null;
    }
    return Number(String(intent.getLongExtra(key, 0)));
  } catch {
    return null;
  }
}

function snapshotIntent(intent: any): IntentSnapshot {
  return {
    identifier: readIntentIdentifier(intent),
    extras: {
      [IRIS_EXTRAS.SESSION_ID]: readIntentStringExtra(intent, IRIS_EXTRAS.SESSION_ID),
      [IRIS_EXTRAS.THREAD_ID]: readIntentStringExtra(intent, IRIS_EXTRAS.THREAD_ID),
      [IRIS_EXTRAS.THREAD_SCOPE]: readIntentIntExtra(intent, IRIS_EXTRAS.THREAD_SCOPE),
      [IRIS_EXTRAS.ROOM_ID]: readIntentStringExtra(intent, IRIS_EXTRAS.ROOM_ID),
      [IRIS_EXTRAS.CREATED_AT]: readIntentLongExtra(intent, IRIS_EXTRAS.CREATED_AT),
    },
  };
}

function applySessionExtras(targetIntent: any, sourceIntent: any): void {
  if (targetIntent == null || sourceIntent == null) {
    return;
  }

  const source = snapshotIntent(sourceIntent);
  const copied = copySessionExtras(source, {});
  if (Object.keys(copied).length === 0) {
    return;
  }

  try {
    for (const [key, value] of Object.entries(copied)) {
      if (typeof value === 'string' || typeof value === 'number') {
        targetIntent.putExtra(key, value);
      }
    }

    const identifier = source.identifier;
    if (identifier != null) {
      try {
        targetIntent.setIdentifier(identifier);
      } catch {
        // 최선 노력만
      }
    }
  } catch {
    // 전파 불가 시 무시
  }
}

// ─── 런타임 헬퍼 ────────────────────────────────────────────────────────────

function readChatRoomId(runtime: RuntimeLike, sendingLog: any): string | null {
  try {
    const JavaBridge = runtime.Java;
    if (JavaBridge == null) {
      return null;
    }
    return JavaBridge.use('java.lang.String').valueOf(sendingLog.getChatRoomId());
  } catch {
    return null;
  }
}

function readAttachmentText(sendingLog: any): string | null {
  try {
    const cls = sendingLog.getClass();
    const attachmentField = cls.getDeclaredField(KAKAO_IMAGE_GRAFT_TARGET.attachmentField);
    attachmentField.setAccessible(true);
    const attachment = attachmentField.get(sendingLog);
    return attachment == null ? null : String(attachment);
  } catch {
    return null;
  }
}

function rewriteAttachmentText(sendingLog: any, newText: string): boolean {
  try {
    const cls = sendingLog.getClass();
    const attachmentField = cls.getDeclaredField(KAKAO_IMAGE_GRAFT_TARGET.attachmentField);
    attachmentField.setAccessible(true);
    const attachment = attachmentField.get(sendingLog);
    if (attachment == null) {
      return false;
    }

    const attachmentClass = attachment.getClass();
    const fields = attachmentClass.getDeclaredFields();
    for (const field of fields) {
      field.setAccessible(true);
      if (field.getType().getName() !== 'java.lang.String') {
        continue;
      }

      const value = field.get(attachment);
      if (value != null && String(value).includes('callingPkg')) {
        field.set(attachment, newText);
        return true;
      }
    }
  } catch {
    return false;
  }

  return false;
}

function readMessageType(sendingLog: any): string | null {
  try {
    return String(sendingLog.w0());
  } catch {
    return null;
  }
}

function readUriStrings(values: any): string[] {
  if (values == null) {
    return [];
  }

  if (Array.isArray(values)) {
    return values.map((value) => String(value));
  }

  try {
    const size = Number(values.size());
    const items: string[] = [];
    for (let index = 0; index < size; index += 1) {
      items.push(String(values.get(index)));
    }
    return items;
  } catch {
    return [];
  }
}

function fingerprintUris(values: any): string {
  return fingerprintUriStrings(readUriStrings(values));
}

// ─── 훅 설치 ────────────────────────────────────────────────────────────────

function installBaseIntentFilterHook(runtime: RuntimeLike, health: ThreadImageGraftHealth): void {
  const JavaBridge = resolveJavaBridge(runtime);
  if (JavaBridge == null) {
    return;
  }

  const BaseIntentFilterActivity = JavaBridge.use(BASE_INTENT_FILTER_TARGET.className);
  const installedAtMs = nowMs(runtime);

  // b6(): 인텐트 extras에서 세션 메타 캡처 → URI 지문으로 저장
  for (const overload of BaseIntentFilterActivity[BASE_INTENT_FILTER_TARGET.captureMethod].overloads) {
    overload.implementation = function (...args: any[]) {
      const timestampMs = nowMs(runtime);
      touchThreadImageHook(health, 'b6', timestampMs);
      const activityIntent = this.getIntent();
      const outerIntent = args[0];
      const nestedIntent = args[1];
      const uriList = args[2];

      const fingerprint = fingerprintUris(uriList);
      const activitySession = readSessionMeta(snapshotIntent(activityIntent));
      const nestedSession = readSessionMeta(snapshotIntent(nestedIntent));
      const merged = mergeSessionMeta(activitySession, nestedSession);

      if (merged != null) {
        fingerprintStore.set(fingerprint, merged);
        recordThreadImageEvent(health, 'capture', 'success', timestampMs, ['b6']);
        emitThreadImageGraftHealth(runtime, health, 'capture', timestampMs);
        log(
          runtime,
          `b6 captured fp=${fingerprint} session=${merged.sessionId} threadId=${merged.threadId ?? 'none'} scope=${merged.threadScope} room=${merged.roomId ?? 'none'}`,
        );
      } else {
        recordThreadImageEvent(health, 'capture', 'skip', timestampMs, ['b6']);
        emitThreadImageGraftHealth(runtime, health, 'capture', timestampMs);
      }

      // 세션 extras를 forward intent로 전파
      applySessionExtras(outerIntent, activityIntent);
      applySessionExtras(nestedIntent, activityIntent);

      return overload.apply(this, args);
    };
  }

  markThreadImageHookInstalled(health, 'b6', installedAtMs);
}

function installChatMediaSenderHooks(runtime: RuntimeLike, health: ThreadImageGraftHealth): void {
  const JavaBridge = resolveJavaBridge(runtime);
  if (JavaBridge == null) {
    return;
  }

  const ChatMediaSender = JavaBridge.use(CHAT_MEDIA_SENDER_TARGET.className);
  const installedAtMs = nowMs(runtime);

  // o(): 미디어 전송 진입점 — try/finally로 currentSession 스코프 관리
  for (const overload of ChatMediaSender[CHAT_MEDIA_SENDER_TARGET.entryMethod].overloads) {
    overload.implementation = function (...args: any[]) {
      const timestampMs = nowMs(runtime);
      const uriList = args[0];
      const fingerprint = fingerprintUris(uriList);
      const session = fingerprintStore.get(fingerprint);
      currentMediaSenderSession = session;
      recordThreadImageEvent(health, 'restore', session == null ? 'skip' : 'success', timestampMs, ['o']);
      emitThreadImageGraftHealth(runtime, health, 'restore', timestampMs);
      log(
        runtime,
        `o entry fp=${fingerprint} session=${session?.sessionId ?? 'none'}`,
      );
      try {
        return overload.apply(this, args);
      } finally {
        currentMediaSenderSession = null;
      }
    };
  }

  // t(): o()에서 세션을 찾지 못한 경우 폴백 복원
  for (const overload of ChatMediaSender[CHAT_MEDIA_SENDER_TARGET.processMethod].overloads) {
    overload.implementation = function (...args: any[]) {
      const timestampMs = nowMs(runtime);
      touchThreadImageHook(health, 't', timestampMs);
      if (currentMediaSenderSession == null) {
        const uriList = args[9];
        const fingerprint = fingerprintUris(uriList);
        const session = fingerprintStore.get(fingerprint);
        if (session != null) {
          currentMediaSenderSession = session;
          recordThreadImageEvent(health, 'restore', 'success', timestampMs, ['t']);
          emitThreadImageGraftHealth(runtime, health, 'restore', timestampMs);
          log(runtime, `t fallback-restore fp=${fingerprint} session=${session.sessionId}`);
        } else {
          recordThreadImageEvent(health, 'restore', 'skip', timestampMs, ['t']);
          emitThreadImageGraftHealth(runtime, health, 'restore', timestampMs);
        }
      }
      try {
        return overload.apply(this, args);
      } finally {
        currentMediaSenderSession = null;
      }
    };
  }

  // A(): ChatSendingLog에 threadId/scope를 H1()/J1() setter로 주입
  for (const overload of ChatMediaSender[CHAT_MEDIA_SENDER_TARGET.injectMethod].overloads) {
    overload.implementation = function (...args: any[]) {
      const sendingLog = args[0];
      const session = currentMediaSenderSession;
      const timestampMs = nowMs(runtime);
      touchThreadImageHook(health, 'A', timestampMs);

      if (session != null) {
        const roomId = readChatRoomId(runtime, sendingLog);
        const plan = planImageGraft(session, roomId ?? '');
        if (plan.status === 'inject' && plan.injection != null) {
          try {
            sendingLog.H1(plan.injection.threadScope);
            sendingLog.J1(
              JavaBridge.use('java.lang.Long').valueOf(
                JavaBridge.use('java.lang.String').valueOf(plan.injection.threadId),
              ),
            );
            recordThreadImageEvent(health, 'inject', 'success', timestampMs, ['A']);
            emitThreadImageGraftHealth(runtime, health, 'inject', timestampMs);
            log(
              runtime,
              `A injected session=${session.sessionId} room=${roomId ?? 'none'} threadId=${plan.injection.threadId} scope=${plan.injection.threadScope}`,
            );
          } catch (error) {
            recordThreadImageEvent(health, 'inject', 'failure', timestampMs, ['A']);
            emitThreadImageGraftHealth(runtime, health, 'inject', timestampMs);
            emitThreadImageGraftHealth(runtime, health, 'readiness', timestampMs);
            log(runtime, `A injection-failed session=${session.sessionId} error=${String(error)}`);
          }
        } else {
          recordThreadImageEvent(health, 'inject', 'skip', timestampMs, ['A']);
          emitThreadImageGraftHealth(runtime, health, 'inject', timestampMs);
          log(runtime, `A skip status=${plan.status} session=${session.sessionId} room=${roomId ?? 'none'}`);
        }
      } else {
        recordThreadImageEvent(health, 'inject', 'skip', timestampMs, ['A']);
        emitThreadImageGraftHealth(runtime, health, 'inject', timestampMs);
      }

      return overload.apply(this, args);
    };
  }

  markThreadImageHookInstalled(health, 'o', installedAtMs);
  markThreadImageHookInstalled(health, 't', installedAtMs);
  markThreadImageHookInstalled(health, 'A', installedAtMs);
}

function installSendingLogHook(runtime: RuntimeLike, health: ThreadImageGraftHealth): void {
  const JavaBridge = resolveJavaBridge(runtime);
  if (JavaBridge == null) {
    return;
  }

  const RequestCompanion = JavaBridge.use(KAKAO_IMAGE_GRAFT_TARGET.className);
  const installedAtMs = nowMs(runtime);

  // u(): Photo 메시지의 callingPkg 제거 + 관찰 로그
  for (const overload of RequestCompanion[KAKAO_IMAGE_GRAFT_TARGET.methodName].overloads) {
    overload.implementation = function (...args: any[]) {
      const timestampMs = nowMs(runtime);
      touchThreadImageHook(health, 'u', timestampMs);
      const sendingLog = args[1];
      const messageType = readMessageType(sendingLog);

      if (isPhotoMessageType(messageType)) {
        const attachmentText = readAttachmentText(sendingLog);
        const cleanedAttachment = removeCallingPkgFromAttachment(attachmentText);
        if (cleanedAttachment != null && cleanedAttachment !== attachmentText) {
          if (rewriteAttachmentText(sendingLog, cleanedAttachment)) {
            log(runtime, `u callingPkg-removed room=${readChatRoomId(runtime, sendingLog) ?? 'none'}`);
          }
        }
      }

      log(runtime, `u observe type=${messageType ?? 'none'} room=${readChatRoomId(runtime, sendingLog) ?? 'none'}`);

      return overload.apply(this, args);
    };
  }

  markThreadImageHookInstalled(health, 'u', installedAtMs);
}

// ─── 공개 진입점 ────────────────────────────────────────────────────────────

export function installThreadImageGraft(runtime: RuntimeLike = globalThis as RuntimeLike): boolean {
  const JavaBridge = resolveJavaBridge(runtime);
  if (JavaBridge == null || typeof JavaBridge.perform !== 'function') {
    return false;
  }

  stopThreadImageGraftHeartbeat();
  const health = createThreadImageGraftHealth(nowMs(runtime));
  emitThreadImageGraftHealth(runtime, health, 'health', health.updatedAtMs);

  JavaBridge.perform(() => {
    const hookingAtMs = nowMs(runtime);
    setThreadImageGraftState(health, 'HOOKING', hookingAtMs, false);
    emitThreadImageGraftHealth(runtime, health, 'health', hookingAtMs);

    const assessment = resolveThreadImageHookAssessment(runtime, hookingAtMs);
    applyThreadImageHookAssessment(health, assessment, hookingAtMs);
    if (assessment.state === 'BLOCKED') {
      emitThreadImageGraftHealth(runtime, health, 'readiness', hookingAtMs);
      emitThreadImageGraftHealth(runtime, health, 'heartbeat', hookingAtMs);
      startThreadImageGraftHeartbeat(runtime, health);
      log(runtime, `thread-image-graft blocked ${missingThreadImageRequirements(health).join(', ')}`);
      return;
    }

    installBaseIntentFilterHook(runtime, health);
    installChatMediaSenderHooks(runtime, health);
    installSendingLogHook(runtime, health);
    const readyAtMs = nowMs(runtime);
    finalizeThreadImageGraftInstall(health, readyAtMs);
    emitThreadImageGraftHealth(runtime, health, 'readiness', readyAtMs);
    emitThreadImageGraftHealth(runtime, health, 'heartbeat', readyAtMs);
    startThreadImageGraftHeartbeat(runtime, health);
    log(runtime, 'thread-image-graft hooks installed (4-hook session-chain)');
  });

  return true;
}

if (JavaBridgeGlobal?.available) {
  installThreadImageGraft({
    ...(globalThis as RuntimeLike),
    Java: JavaBridgeGlobal,
  });
}
