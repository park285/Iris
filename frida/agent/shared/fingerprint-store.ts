import { type SessionMeta } from './session.js';

const DEFAULT_TTL_MS = 60_000;
const DEFAULT_MAX_SIZE = 32;

export function fingerprintUriStrings(uris: string[]): string {
  if (uris.length === 0) {
    return 'empty';
  }

  return `${uris.length}:${uris.join('|')}`;
}

export function mergeSessionMeta(
  previous: SessionMeta | null,
  next: SessionMeta | null,
): SessionMeta | null {
  if (previous == null) {
    return next;
  }

  if (next == null) {
    return previous;
  }

  const prevTime = previous.createdAt > 0 ? previous.createdAt : Infinity;
  const nextTime = next.createdAt > 0 ? next.createdAt : Infinity;
  const createdAt = Math.min(prevTime, nextTime);

  return {
    sessionId: previous.sessionId,
    threadId: previous.threadId ?? next.threadId,
    threadScope: previous.threadScope > 0 ? previous.threadScope : next.threadScope,
    roomId: previous.roomId ?? next.roomId,
    createdAt: createdAt === Infinity ? Date.now() : createdAt,
  };
}

export class FingerprintSessionStore {
  private readonly entries = new Map<string, SessionMeta>();
  private readonly ttlMs: number;
  private readonly maxSize: number;

  constructor(ttlMs = DEFAULT_TTL_MS, maxSize = DEFAULT_MAX_SIZE) {
    this.ttlMs = ttlMs;
    this.maxSize = maxSize;
  }

  set(fingerprint: string, session: SessionMeta): void {
    this.cleanup();

    const existing = this.entries.get(fingerprint) ?? null;
    this.entries.set(fingerprint, mergeSessionMeta(existing, session)!);
    this.evictOldest();
  }

  get(fingerprint: string): SessionMeta | null {
    this.cleanup();

    return this.entries.get(fingerprint) ?? null;
  }

  get size(): number {
    return this.entries.size;
  }

  private cleanup(now = Date.now()): void {
    for (const [fingerprint, session] of this.entries) {
      if (now - session.createdAt > this.ttlMs) {
        this.entries.delete(fingerprint);
      }
    }
  }

  private evictOldest(): void {
    while (this.entries.size > this.maxSize) {
      const first = this.entries.keys().next();
      if (first.done) {
        break;
      }

      this.entries.delete(first.value);
    }
  }
}
