export const IRIS_EXTRAS = {
  SESSION_ID: 'party.qwer.iris.extra.SHARE_SESSION_ID',
  THREAD_ID: 'party.qwer.iris.extra.THREAD_ID',
  THREAD_SCOPE: 'party.qwer.iris.extra.THREAD_SCOPE',
  ROOM_ID: 'party.qwer.iris.extra.ROOM_ID',
  CREATED_AT: 'party.qwer.iris.extra.CREATED_AT',
} as const;

const IRIS_IDENTIFIER_PREFIX = 'iris:';

export type SessionMeta = {
  sessionId: string;
  threadId: string | null;
  threadScope: number;
  roomId: string | null;
  createdAt: number;
};

export type IntentSnapshot = {
  identifier?: string | null;
  extras?: Record<string, unknown> | null;
};

function readStringValue(value: unknown): string | null {
  if (typeof value === 'string') {
    const trimmed = value.trim();
    return trimmed.length > 0 ? trimmed : null;
  }

  if (typeof value === 'number' || typeof value === 'bigint') {
    return String(value);
  }

  return null;
}

function readNumberValue(value: unknown, fallback: number): number {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return Math.trunc(value);
  }

  if (typeof value === 'string') {
    const parsed = Number.parseInt(value, 10);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }

  return fallback;
}

export function sessionIdFromIdentifier(identifier: string | null | undefined): string | null {
  if (identifier == null || !identifier.startsWith(IRIS_IDENTIFIER_PREFIX)) {
    return null;
  }

  const sessionId = identifier.slice(IRIS_IDENTIFIER_PREFIX.length).trim();
  return sessionId.length > 0 ? sessionId : null;
}

export function readSessionMeta(snapshot: IntentSnapshot): SessionMeta | null {
  const extras = snapshot.extras ?? {};
  const sessionId =
    readStringValue(extras[IRIS_EXTRAS.SESSION_ID]) ?? sessionIdFromIdentifier(snapshot.identifier);
  if (sessionId == null) {
    return null;
  }

  return {
    sessionId,
    threadId: readStringValue(extras[IRIS_EXTRAS.THREAD_ID]),
    threadScope: readNumberValue(extras[IRIS_EXTRAS.THREAD_SCOPE], 0),
    roomId: readStringValue(extras[IRIS_EXTRAS.ROOM_ID]),
    createdAt: readNumberValue(extras[IRIS_EXTRAS.CREATED_AT], Date.now()),
  };
}

export function copySessionExtras(
  source: IntentSnapshot,
  targetExtras: Record<string, unknown>,
): Record<string, unknown> {
  const session = readSessionMeta(source);
  if (session == null) {
    return { ...targetExtras };
  }

  const next: Record<string, unknown> = {
    ...targetExtras,
    [IRIS_EXTRAS.SESSION_ID]: session.sessionId,
    [IRIS_EXTRAS.THREAD_SCOPE]: session.threadScope,
    [IRIS_EXTRAS.CREATED_AT]: session.createdAt,
  };

  if (session.threadId != null) {
    next[IRIS_EXTRAS.THREAD_ID] = session.threadId;
  }

  if (session.roomId != null) {
    next[IRIS_EXTRAS.ROOM_ID] = session.roomId;
  }

  return next;
}

export type SendingLogDedupeInput = {
  clientMessageId?: string | number | bigint | null;
  identityHash?: string | number | null;
  roomId?: string | null;
  messageType?: string | null;
};

export function buildSendingLogDedupeKey(input: SendingLogDedupeInput): string | null {
  const clientMessageId = readStringValue(input.clientMessageId);
  if (clientMessageId != null && clientMessageId !== '0') {
    return `client:${clientMessageId}`;
  }

  const identityHash = readStringValue(input.identityHash);
  if (identityHash == null) {
    return null;
  }

  return `identity:${input.roomId ?? 'unknown'}:${input.messageType ?? 'unknown'}:${identityHash}`;
}
