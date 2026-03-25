import { KAKAO_IMAGE_GRAFT_TARGET } from './shared/kakao.js';
import {
  buildSendingLogDedupeKey,
  copySessionExtras,
  readSessionMeta,
  type IntentSnapshot,
  type SessionMeta,
} from './shared/session.js';
import { isPhotoMessageType, removeCallingPkgFromAttachment } from './shared/message.js';

type RuntimeLike = {
  Java?: {
    available?: boolean;
    perform: (callback: () => void) => void;
    use: (className: string) => any;
  };
  send?: (message: string) => void;
};

const BASE_INTENT_FILTER_ACTIVITY = 'com.kakao.talk.activity.f';
const TASK_ROOT_ACTIVITY = 'com.kakao.talk.activity.TaskRootActivity';
const CHAT_ROOM_HOLDER_ACTIVITY = 'com.kakao.talk.activity.chatroom.ChatRoomHolderActivity';
const CHAT_ROOM_FRAGMENT = 'com.kakao.talk.activity.chatroom.c';
const CHAT_MEDIA_SENDER = 'bh.c';
const MEDIA_BATCH_WORKER = 'Fp.N';
const NESTED_SEND_INTENT_KEYS = ['EXTRA_SEND_INTENT', 'ConnectManager.ACTION_SEND_INTENT'] as const;
const THREAD_KEYS = ['intent_key_join_thread_id', 'BUNDLE_KEY_THREAD_CHATLOG_ID', 'BUNDLE_KEY_CHAT_ROOM_ID'] as const;
const DUPLICATE_TTL_MS = 60_000;
const DISCOVERY_BRIDGE_EXTRAS = true;

const seenSendingLogs = new Map<string, number>();
const sessionByFingerprint = new Map<string, SessionMeta>();
let dumpedChatMediaSenderClass = false;
let currentChatMediaSenderSession: SessionMeta | null = null;

function log(runtime: RuntimeLike, message: string): void {
  runtime.send?.(`[discovery] ${message}`);
}

function trimText(value: string | null | undefined, maxLength = 240): string {
  if (value == null) {
    return 'null';
  }
  if (value.length <= maxLength) {
    return value;
  }
  return `${value.slice(0, maxLength)}...`;
}

function cleanupSeenSendingLogs(now = Date.now()): void {
  for (const [key, seenAt] of seenSendingLogs.entries()) {
    if (now - seenAt > DUPLICATE_TTL_MS) {
      seenSendingLogs.delete(key);
    }
  }
}

function cleanupFingerprintSessions(now = Date.now()): void {
  for (const [fingerprint, session] of sessionByFingerprint.entries()) {
    if (now - session.createdAt > DUPLICATE_TTL_MS) {
      sessionByFingerprint.delete(fingerprint);
    }
  }
}

function mergeSessionMeta(previous: SessionMeta | null, next: SessionMeta | null): SessionMeta | null {
  if (previous == null) {
    return next;
  }
  if (next == null) {
    return previous;
  }

  return {
    sessionId: previous.sessionId,
    threadId: previous.threadId ?? next.threadId,
    threadScope: previous.threadScope > 0 ? previous.threadScope : next.threadScope,
    roomId: previous.roomId ?? next.roomId,
    createdAt:
      previous.createdAt > 0 && previous.createdAt <= next.createdAt
        ? previous.createdAt
        : next.createdAt,
  };
}

function rememberSendingLog(key: string | null, now = Date.now()): boolean {
  if (key == null) {
    return true;
  }

  cleanupSeenSendingLogs(now);
  if (seenSendingLogs.has(key)) {
    return false;
  }

  seenSendingLogs.set(key, now);
  return true;
}

function readNestedIntent(intent: any): any | null {
  if (intent == null) {
    return null;
  }

  for (const key of NESTED_SEND_INTENT_KEYS) {
    try {
      const nested = intent.getParcelableExtra(key);
      if (nested != null) {
        return nested;
      }
    } catch {
      // Ignore and try the next key.
    }
  }

  return null;
}

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

function readIntentExtras(intent: any): Record<string, unknown> {
  if (intent == null) {
    return {};
  }

  const extras: Record<string, unknown> = {};
  const meta = readSessionMeta({
    identifier: readIntentIdentifier(intent),
    extras: {
      'party.qwer.iris.extra.SHARE_SESSION_ID': readIntentStringExtra(intent, 'party.qwer.iris.extra.SHARE_SESSION_ID'),
      'party.qwer.iris.extra.THREAD_ID': readIntentStringExtra(intent, 'party.qwer.iris.extra.THREAD_ID'),
      'party.qwer.iris.extra.THREAD_SCOPE': readIntentIntExtra(intent, 'party.qwer.iris.extra.THREAD_SCOPE'),
      'party.qwer.iris.extra.ROOM_ID': readIntentStringExtra(intent, 'party.qwer.iris.extra.ROOM_ID'),
      'party.qwer.iris.extra.CREATED_AT': readIntentLongExtra(intent, 'party.qwer.iris.extra.CREATED_AT'),
    },
  });
  if (meta == null) {
    return extras;
  }

  return copySessionExtras(
    {
      identifier: meta.sessionId == null ? null : `iris:${meta.sessionId}`,
      extras: {
        'party.qwer.iris.extra.SHARE_SESSION_ID': meta.sessionId,
        'party.qwer.iris.extra.THREAD_ID': meta.threadId,
        'party.qwer.iris.extra.THREAD_SCOPE': meta.threadScope,
        'party.qwer.iris.extra.ROOM_ID': meta.roomId,
        'party.qwer.iris.extra.CREATED_AT': meta.createdAt,
      },
    },
    {},
  );
}

function snapshotIntent(intent: any): IntentSnapshot {
  return {
    identifier: readIntentIdentifier(intent),
    extras: readIntentExtras(intent),
  };
}

function applySessionExtras(runtime: RuntimeLike, targetIntent: any, sourceIntent: any, label: string): void {
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
      if (typeof value === 'string') {
        targetIntent.putExtra(key, value);
      } else if (typeof value === 'number') {
        targetIntent.putExtra(key, value);
      }
    }

    const identifier = source.identifier;
    if (identifier != null) {
      try {
        targetIntent.setIdentifier(identifier);
      } catch {
        // Best effort only.
      }
    }
    log(runtime, `${label} bridged session=${trimText(identifier ?? null, 64)}`);
  } catch (error) {
    log(runtime, `${label} bridge failed error=${String(error)}`);
  }
}

function readIntentAction(intent: any): string | null {
  if (intent == null) {
    return null;
  }

  try {
    const action = intent.getAction();
    return action == null ? null : String(action);
  } catch {
    return null;
  }
}

function readIntentType(intent: any): string | null {
  if (intent == null) {
    return null;
  }

  try {
    const type = intent.getType();
    return type == null ? null : String(type);
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

function countUris(uriList: any): number {
  if (uriList == null) {
    return 0;
  }

  try {
    return Number(uriList.size());
  } catch {
    return 0;
  }
}

function countCollection(values: any): number {
  if (values == null) {
    return 0;
  }

  try {
    return Number(values.size());
  } catch {
    // Ignore and try array semantics.
  }

  if (Array.isArray(values)) {
    return values.length;
  }

  return 0;
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
  const uris = readUriStrings(values);
  if (uris.length === 0) {
    return 'empty';
  }
  return `${uris.length}:${uris.join('|')}`;
}

function formatSession(intent: any): string {
  const meta = readSessionMeta(snapshotIntent(intent));
  if (meta == null) {
    return 'session=none';
  }

  return `session=${meta.sessionId} threadId=${meta.threadId ?? 'none'} scope=${meta.threadScope} room=${meta.roomId ?? 'none'} createdAt=${meta.createdAt}`;
}

function logIntent(runtime: RuntimeLike, label: string, intent: any): void {
  if (intent == null) {
    log(runtime, `${label} intent=null`);
    return;
  }

  log(
    runtime,
    `${label} action=${trimText(readIntentAction(intent), 96)} type=${trimText(readIntentType(intent), 96)} identifier=${trimText(readIntentIdentifier(intent), 96)} ${formatSession(intent)}`,
  );
}

function readMessageType(sendingLog: any): string | null {
  try {
    return String(sendingLog.w0());
  } catch {
    return null;
  }
}

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

function readClientMessageId(sendingLog: any): string | null {
  try {
    return String(sendingLog.T());
  } catch {
    return null;
  }
}

function readThreadId(sendingLog: any): string | null {
  try {
    const threadId = sendingLog.r0();
    return threadId == null ? null : String(threadId);
  } catch {
    return null;
  }
}

function readThreadScope(sendingLog: any): number | null {
  try {
    return Number(sendingLog.m0());
  } catch {
    return null;
  }
}

function readForwardState(sendingLog: any): string | null {
  try {
    const value = sendingLog.t();
    return value == null ? null : String(value);
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

function installBaseIntentFilterDiscovery(runtime: RuntimeLike): void {
  const JavaBridge = runtime.Java;
  if (JavaBridge == null) {
    return;
  }

  const BaseIntentFilterActivity = JavaBridge.use(BASE_INTENT_FILTER_ACTIVITY);

  for (const overload of BaseIntentFilterActivity.b6.overloads) {
    overload.implementation = function (...args: any[]) {
      const activityIntent = this.getIntent();
      const outerIntent = args[0];
      const nestedIntent = args[1];
      const uriList = args[2];

      logIntent(runtime, 'BaseIntentFilterActivity.b6.activity', activityIntent);
      logIntent(runtime, 'BaseIntentFilterActivity.b6.outer', outerIntent);
      logIntent(runtime, 'BaseIntentFilterActivity.b6.nested', nestedIntent);
      log(runtime, `BaseIntentFilterActivity.b6.uris count=${countUris(uriList)}`);

      const fingerprint = fingerprintUris(uriList);
      const activitySession = readSessionMeta(snapshotIntent(activityIntent));
      const nestedSession = readSessionMeta(snapshotIntent(nestedIntent));
      const mergedSession = mergeSessionMeta(activitySession, nestedSession);
      if (mergedSession != null) {
        cleanupFingerprintSessions();
        sessionByFingerprint.set(
          fingerprint,
          mergeSessionMeta(sessionByFingerprint.get(fingerprint) ?? null, mergedSession)!,
        );
        log(runtime, `BaseIntentFilterActivity.b6.session fp=${trimText(fingerprint, 200)} session=${mergedSession.sessionId} threadId=${mergedSession.threadId ?? 'none'} scope=${mergedSession.threadScope}`);
      }

      if (DISCOVERY_BRIDGE_EXTRAS) {
        applySessionExtras(runtime, outerIntent, activityIntent, 'BaseIntentFilterActivity.b6.outer');
        applySessionExtras(runtime, nestedIntent, activityIntent, 'BaseIntentFilterActivity.b6.nested');
      }

      return overload.apply(this, args);
    };
  }

  for (const overload of BaseIntentFilterActivity.Z5.overloads) {
    overload.implementation = function (...args: any[]) {
      const activityIntent = this.getIntent();
      const forwardIntent = args[0];
      const nestedIntent = readNestedIntent(forwardIntent);

      logIntent(runtime, 'BaseIntentFilterActivity.Z5.activity', activityIntent);
      logIntent(runtime, 'BaseIntentFilterActivity.Z5.forward', forwardIntent);
      logIntent(runtime, 'BaseIntentFilterActivity.Z5.nested', nestedIntent);

      if (DISCOVERY_BRIDGE_EXTRAS) {
        applySessionExtras(runtime, forwardIntent, activityIntent, 'BaseIntentFilterActivity.Z5.forward');
        applySessionExtras(runtime, nestedIntent, activityIntent, 'BaseIntentFilterActivity.Z5.nested');
      }

      return overload.apply(this, args);
    };
  }
}

function installTaskRootDiscovery(runtime: RuntimeLike): void {
  const JavaBridge = runtime.Java;
  if (JavaBridge == null) {
    return;
  }

  const TaskRootActivity = JavaBridge.use(TASK_ROOT_ACTIVITY);
  for (const overload of TaskRootActivity.c5.overloads) {
    overload.implementation = function (...args: any[]) {
      logIntent(runtime, 'TaskRootActivity.c5', args[0]);
      return overload.apply(this, args);
    };
  }
}

function installChatRoomDiscovery(runtime: RuntimeLike): void {
  const JavaBridge = runtime.Java;
  if (JavaBridge == null) {
    return;
  }

  const ChatRoomHolderActivity = JavaBridge.use(CHAT_ROOM_HOLDER_ACTIVITY);
  for (const overload of ChatRoomHolderActivity.onCreate.overloads) {
    overload.implementation = function (...args: any[]) {
      logIntent(runtime, 'ChatRoomHolderActivity.onCreate', this.getIntent());
      return overload.apply(this, args);
    };
  }

  for (const overload of ChatRoomHolderActivity.onNewIntent.overloads) {
    overload.implementation = function (...args: any[]) {
      const intent = args[0];
      const threadFlags = THREAD_KEYS
        .filter((key) => {
          try {
            return intent?.hasExtra(key) === true;
          } catch {
            return false;
          }
        })
        .join(',');

      logIntent(runtime, 'ChatRoomHolderActivity.onNewIntent', intent);
      log(runtime, `ChatRoomHolderActivity.onNewIntent.threadFlags=${threadFlags || 'none'}`);
      return overload.apply(this, args);
    };
  }
}

function installChatRoomSendDiscovery(runtime: RuntimeLike): void {
  const JavaBridge = runtime.Java;
  if (JavaBridge == null) {
    return;
  }

  const ChatRoomFragment = JavaBridge.use(CHAT_ROOM_FRAGMENT);
  for (const overload of ChatRoomFragment.cf.overloads) {
    overload.implementation = function (...args: any[]) {
      const fragmentIntent =
        typeof this.K9 === 'function'
          ? this.K9()
          : null;
      const uriList = args[0];
      const messageType = args[1];
      const message = args[2];
      const attachment = args[3];
      const forwardExtra = args[4];
      const writeType = args[5];
      const shareOriginal = args[6];
      const needHighQualityVideo = args[7];
      const session = readSessionMeta(snapshotIntent(fragmentIntent));
      const fingerprint = fingerprintUris(uriList);

      if (session != null) {
        cleanupFingerprintSessions();
        sessionByFingerprint.set(
          fingerprint,
          mergeSessionMeta(sessionByFingerprint.get(fingerprint) ?? null, session)!,
        );
      }

      if (!dumpedChatMediaSenderClass) {
        dumpedChatMediaSenderClass = true;
        try {
          const sender = this.i9();
          const senderClass = sender.getClass();
          log(runtime, `ChatRoomFragment.cf.sender class=${String(senderClass.getName())}`);
          const methods = senderClass.getDeclaredMethods();
          for (let index = 0; index < methods.length; index += 1) {
            const method = methods[index];
            const parameterTypes = method.getParameterTypes();
            const params: string[] = [];
            for (let paramIndex = 0; paramIndex < parameterTypes.length; paramIndex += 1) {
              params.push(String(parameterTypes[paramIndex].getName()));
            }
            log(runtime, `ChatRoomFragment.cf.sender method=${String(method.getName())} params=${params.join(',')}`);
          }
        } catch (error) {
          log(runtime, `ChatRoomFragment.cf.sender-dump failed error=${String(error)}`);
        }
      }

      logIntent(runtime, 'ChatRoomFragment.cf.intent', fragmentIntent);
      log(
        runtime,
        `ChatRoomFragment.cf type=${trimText(messageType?.toString?.() ?? null, 96)} uris=${countCollection(uriList)} fp=${trimText(fingerprint, 200)} writeType=${trimText(writeType?.toString?.() ?? null, 64)} shareOriginal=${String(shareOriginal)} highQuality=${String(needHighQualityVideo)} message=${trimText(message?.toString?.() ?? null, 96)} attachment=${trimText(attachment?.toString?.() ?? null, 240)} forwardExtra=${trimText(forwardExtra?.toString?.() ?? null, 240)} session=${session?.sessionId ?? 'none'}`,
      );
      return overload.apply(this, args);
    };
  }
}

function installMediaBatchDiscovery(runtime: RuntimeLike): void {
  const JavaBridge = runtime.Java;
  if (JavaBridge == null) {
    return;
  }

  const MediaBatchWorker = JavaBridge.use(MEDIA_BATCH_WORKER);
  for (const overload of MediaBatchWorker.$init.overloads) {
    overload.implementation = function (...args: any[]) {
      this.$init(...args);
      const targetId = args[0];
      const uriList = args[1];
      const message = args[2];
      const originAttachment = args[3];
      const originForwardExtra = args[4];
      const fingerprint = fingerprintUris(uriList);
      const session = sessionByFingerprint.get(fingerprint) ?? null;

      log(
        runtime,
        `Fp.N.$init target=${trimText(targetId?.toString?.() ?? null, 96)} uris=${countCollection(uriList)} fp=${trimText(fingerprint, 200)} message=${trimText(message?.toString?.() ?? null, 96)} originAttachment=${trimText(originAttachment?.toString?.() ?? null, 240)} originForwardExtra=${trimText(originForwardExtra?.toString?.() ?? null, 240)} session=${session?.sessionId ?? 'none'}`,
      );
    };
  }

  for (const overload of MediaBatchWorker.h.overloads) {
    overload.implementation = function (...args: any[]) {
      try {
        const uriList = this.b?.value ?? null;
        const fingerprint = fingerprintUris(uriList);
        const session = sessionByFingerprint.get(fingerprint) ?? null;
        log(runtime, `Fp.N.h fp=${trimText(fingerprint, 200)} session=${session?.sessionId ?? 'none'}`);
      } catch {
        // Ignore read failures.
      }
      return overload.apply(this, args);
    };
  }
}

function installChatMediaSenderDiscovery(runtime: RuntimeLike): void {
  const JavaBridge = runtime.Java;
  if (JavaBridge == null) {
    return;
  }

  const ChatMediaSender = JavaBridge.use(CHAT_MEDIA_SENDER);
  try {
    const methods = ChatMediaSender.class.getDeclaredMethods();
    for (let index = 0; index < methods.length; index += 1) {
      const method = methods[index];
      const parameterTypes = method.getParameterTypes();
      const params: string[] = [];
      for (let paramIndex = 0; paramIndex < parameterTypes.length; paramIndex += 1) {
        params.push(String(parameterTypes[paramIndex].getName()));
      }
      log(runtime, `ChatMediaSender.method name=${String(method.getName())} params=${params.join(',')}`);
    }
  } catch (error) {
    log(runtime, `ChatMediaSender.method-dump failed error=${String(error)}`);
  }

  const logState = (label: string, self: any, args: any[]) => {
    log(
      runtime,
      `${label} args=${args.map((arg) => trimText(arg?.toString?.() ?? null, 180)).join(' | ')}`,
    );
  };

  for (const overload of ChatMediaSender.o.overloads) {
    overload.implementation = function (...args: any[]) {
      const uriList = args[0];
      const messageType = args[1];
      const message = args[2];
      const attachment = args[3];
      const forwardExtra = args[4];
      const writeType = args[5];
      const shareOriginal = args[6];
      const needHighQualityVideo = args[7];
      const fingerprint = fingerprintUris(uriList);
      const session = sessionByFingerprint.get(fingerprint) ?? null;

      log(
        runtime,
        `ChatMediaSender.o type=${trimText(messageType?.toString?.() ?? null, 96)} uris=${countCollection(uriList)} fp=${trimText(fingerprint, 200)} writeType=${trimText(writeType?.toString?.() ?? null, 64)} shareOriginal=${String(shareOriginal)} highQuality=${String(needHighQualityVideo)} message=${trimText(message?.toString?.() ?? null, 96)} attachment=${trimText(attachment?.toString?.() ?? null, 240)} forwardExtra=${trimText(forwardExtra?.toString?.() ?? null, 240)} session=${session?.sessionId ?? 'none'}`,
      );
      currentChatMediaSenderSession = session;
      try {
        return overload.apply(this, args);
      } finally {
        currentChatMediaSenderSession = null;
      }
    };
  }

  for (const overload of ChatMediaSender.p.overloads) {
    overload.implementation = function (...args: any[]) {
      logState(`ChatMediaSender.p session=${currentChatMediaSenderSession?.sessionId ?? 'none'}`, this, args);
      return overload.apply(this, args);
    };
  }

  for (const overload of ChatMediaSender.u.overloads) {
    overload.implementation = function (...args: any[]) {
      const uriList = args[7];
      const fingerprint = fingerprintUris(uriList);
      const session = sessionByFingerprint.get(fingerprint) ?? currentChatMediaSenderSession;
      logState(`ChatMediaSender.u session=${session?.sessionId ?? 'none'} fp=${trimText(fingerprint, 200)}`, this, args);
      currentChatMediaSenderSession = session;
      try {
        return overload.apply(this, args);
      } finally {
        currentChatMediaSenderSession = null;
      }
    };
  }

  for (const overload of ChatMediaSender.t.overloads) {
    overload.implementation = function (...args: any[]) {
      const uriList = args[9];
      const fingerprint = fingerprintUris(uriList);
      const session = sessionByFingerprint.get(fingerprint) ?? currentChatMediaSenderSession;
      logState(`ChatMediaSender.t session=${session?.sessionId ?? 'none'} fp=${trimText(fingerprint, 200)}`, this, args);
      currentChatMediaSenderSession = session;
      try {
        return overload.apply(this, args);
      } finally {
        currentChatMediaSenderSession = null;
      }
    };
  }

  for (const overload of ChatMediaSender.A.overloads) {
    overload.implementation = function (...args: any[]) {
      const sendingLog = args[0];
      const writeType = args[1];
      const session = currentChatMediaSenderSession;
      if (session != null) {
        const roomId = readChatRoomId(runtime, sendingLog);
        if (roomId === session.roomId && session.threadId != null && session.threadScope > 0) {
          try {
            const JavaBridge = runtime.Java;
            if (JavaBridge != null) {
              sendingLog.H1(session.threadScope);
              sendingLog.J1(
                JavaBridge.use('java.lang.Long').valueOf(
                  JavaBridge.use('java.lang.String').valueOf(session.threadId),
                ),
              );
              log(
                runtime,
                `ChatMediaSender.A injected session=${session.sessionId} room=${roomId} threadId=${session.threadId} scope=${session.threadScope}`,
              );
            }
          } catch (error) {
            log(runtime, `ChatMediaSender.A injection-failed session=${session.sessionId} error=${String(error)}`);
          }
        }
      }
      log(
        runtime,
        `ChatMediaSender.A session=${session?.sessionId ?? 'none'} writeType=${trimText(writeType?.toString?.() ?? null, 64)} room=${readChatRoomId(runtime, sendingLog) ?? 'none'} threadId=${readThreadId(sendingLog) ?? 'none'} scope=${readThreadScope(sendingLog) ?? 'none'} attachment=${trimText(readAttachmentText(sendingLog), 240)}`,
      );
      return overload.apply(this, args);
    };
  }

  for (const overload of ChatMediaSender.B.overloads) {
    overload.implementation = function (...args: any[]) {
      const sendingLog = args[1];
      const writeType = args[2];
      log(
        runtime,
        `ChatMediaSender.B session=${currentChatMediaSenderSession?.sessionId ?? 'none'} writeType=${trimText(writeType?.toString?.() ?? null, 64)} room=${readChatRoomId(runtime, sendingLog) ?? 'none'} threadId=${readThreadId(sendingLog) ?? 'none'} scope=${readThreadScope(sendingLog) ?? 'none'} attachment=${trimText(readAttachmentText(sendingLog), 240)}`,
      );
      return overload.apply(this, args);
    };
  }
}

function installSendingLogDiscovery(runtime: RuntimeLike): void {
  const JavaBridge = runtime.Java;
  if (JavaBridge == null) {
    return;
  }

  const System = JavaBridge.use('java.lang.System');
  const RequestCompanion = JavaBridge.use(KAKAO_IMAGE_GRAFT_TARGET.className);
  const overloads = RequestCompanion[KAKAO_IMAGE_GRAFT_TARGET.methodName].overloads;

  for (const overload of overloads) {
    overload.implementation = function (...args: any[]) {
      const sendingLog = args[1];
      const messageType = readMessageType(sendingLog);
      const roomId = readChatRoomId(runtime, sendingLog);
      const clientMessageId = readClientMessageId(sendingLog);
      const identityHash = Number(System.identityHashCode(sendingLog));
      const dedupeKey = buildSendingLogDedupeKey({
        clientMessageId,
        identityHash,
        roomId,
        messageType,
      });
      const firstSeen = rememberSendingLog(dedupeKey);

      if (firstSeen) {
        log(
          runtime,
          `u.observe key=${dedupeKey ?? 'none'} type=${messageType ?? 'none'} room=${roomId ?? 'none'} client=${clientMessageId ?? 'none'} threadId=${readThreadId(sendingLog) ?? 'none'} scope=${readThreadScope(sendingLog) ?? 'none'} t=${trimText(readForwardState(sendingLog), 240)}`,
        );
      }

      if (firstSeen && isPhotoMessageType(messageType)) {
        const attachmentText = readAttachmentText(sendingLog);
        const cleanedAttachment = removeCallingPkgFromAttachment(attachmentText);
        if (cleanedAttachment != null && cleanedAttachment !== attachmentText) {
          if (rewriteAttachmentText(sendingLog, cleanedAttachment)) {
            log(runtime, `u.observe callingPkg-removed key=${dedupeKey ?? 'none'} attachment=${trimText(cleanedAttachment)}`);
          }
        }
      }

      return overload.apply(this, args);
    };
  }
}

export function installThreadImageDiscovery(runtime: RuntimeLike = globalThis as RuntimeLike): boolean {
  const JavaBridge = runtime.Java;
  if (JavaBridge == null || typeof JavaBridge.perform !== 'function') {
    return false;
  }

  JavaBridge.perform(() => {
    installBaseIntentFilterDiscovery(runtime);
    installTaskRootDiscovery(runtime);
    installChatRoomDiscovery(runtime);
    installChatRoomSendDiscovery(runtime);
    installChatMediaSenderDiscovery(runtime);
    installMediaBatchDiscovery(runtime);
    installSendingLogDiscovery(runtime);
    log(runtime, 'thread-image-discovery hooks installed');
  });

  return true;
}

if ((globalThis as RuntimeLike).Java?.available) {
  installThreadImageDiscovery();
}
