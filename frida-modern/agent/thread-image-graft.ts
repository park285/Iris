import {
  BASE_INTENT_FILTER_TARGET,
  CHAT_MEDIA_SENDER_TARGET,
  KAKAO_IMAGE_GRAFT_TARGET,
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

type RuntimeLike = {
  Java?: {
    available?: boolean;
    perform: (callback: () => void) => void;
    use: (className: string) => any;
  };
  send?: (message: string) => void;
};

// ─── 결과 타입 ───────────────────────────────────────────────────────────────

export type ImageGraftPlan = {
  status: 'skip-no-session' | 'skip-no-thread' | 'skip-room-mismatch' | 'inject';
  injection: {
    threadId: string;
    threadScope: number;
  } | null;
};

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

function installBaseIntentFilterHook(runtime: RuntimeLike): void {
  const JavaBridge = runtime.Java;
  if (JavaBridge == null) {
    return;
  }

  const BaseIntentFilterActivity = JavaBridge.use(BASE_INTENT_FILTER_TARGET.className);

  // b6(): 인텐트 extras에서 세션 메타 캡처 → URI 지문으로 저장
  for (const overload of BaseIntentFilterActivity[BASE_INTENT_FILTER_TARGET.captureMethod].overloads) {
    overload.implementation = function (...args: any[]) {
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
        log(
          runtime,
          `b6 captured fp=${fingerprint} session=${merged.sessionId} threadId=${merged.threadId ?? 'none'} scope=${merged.threadScope} room=${merged.roomId ?? 'none'}`,
        );
      }

      // 세션 extras를 forward intent로 전파
      applySessionExtras(outerIntent, activityIntent);
      applySessionExtras(nestedIntent, activityIntent);

      return overload.apply(this, args);
    };
  }
}

function installChatMediaSenderHooks(runtime: RuntimeLike): void {
  const JavaBridge = runtime.Java;
  if (JavaBridge == null) {
    return;
  }

  const ChatMediaSender = JavaBridge.use(CHAT_MEDIA_SENDER_TARGET.className);

  // o(): 미디어 전송 진입점 — try/finally로 currentSession 스코프 관리
  for (const overload of ChatMediaSender[CHAT_MEDIA_SENDER_TARGET.entryMethod].overloads) {
    overload.implementation = function (...args: any[]) {
      const uriList = args[0];
      const fingerprint = fingerprintUris(uriList);
      const session = fingerprintStore.get(fingerprint);
      currentMediaSenderSession = session;
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
      if (currentMediaSenderSession == null) {
        const uriList = args[9];
        const fingerprint = fingerprintUris(uriList);
        const session = fingerprintStore.get(fingerprint);
        if (session != null) {
          currentMediaSenderSession = session;
          log(runtime, `t fallback-restore fp=${fingerprint} session=${session.sessionId}`);
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
            log(
              runtime,
              `A injected session=${session.sessionId} room=${roomId ?? 'none'} threadId=${plan.injection.threadId} scope=${plan.injection.threadScope}`,
            );
          } catch (error) {
            log(runtime, `A injection-failed session=${session.sessionId} error=${String(error)}`);
          }
        } else {
          log(runtime, `A skip status=${plan.status} session=${session.sessionId} room=${roomId ?? 'none'}`);
        }
      }

      return overload.apply(this, args);
    };
  }
}

function installSendingLogHook(runtime: RuntimeLike): void {
  const JavaBridge = runtime.Java;
  if (JavaBridge == null) {
    return;
  }

  const RequestCompanion = JavaBridge.use(KAKAO_IMAGE_GRAFT_TARGET.className);

  // u(): Photo 메시지의 callingPkg 제거 + 관찰 로그
  for (const overload of RequestCompanion[KAKAO_IMAGE_GRAFT_TARGET.methodName].overloads) {
    overload.implementation = function (...args: any[]) {
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
}

// ─── 공개 진입점 ────────────────────────────────────────────────────────────

export function installThreadImageGraft(runtime: RuntimeLike = globalThis as RuntimeLike): boolean {
  const JavaBridge = runtime.Java;
  if (JavaBridge == null || typeof JavaBridge.perform !== 'function') {
    return false;
  }

  JavaBridge.perform(() => {
    installBaseIntentFilterHook(runtime);
    installChatMediaSenderHooks(runtime);
    installSendingLogHook(runtime);
    log(runtime, 'thread-image-graft hooks installed (4-hook session-chain)');
  });

  return true;
}

if ((globalThis as RuntimeLike).Java?.available) {
  installThreadImageGraft();
}
