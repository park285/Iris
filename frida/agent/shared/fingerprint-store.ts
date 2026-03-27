import { type SessionMeta } from './session.js';

const DEFAULT_TTL_MS = 60_000;
const DEFAULT_MAX_SIZE = 32;
const DEFAULT_SWEEP_INTERVAL_MS = 5_000;

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
  private readonly sweepIntervalMs: number;
  private lastSweepAt = 0;

  constructor(
    ttlMs = DEFAULT_TTL_MS,
    maxSize = DEFAULT_MAX_SIZE,
    sweepIntervalMs = DEFAULT_SWEEP_INTERVAL_MS,
  ) {
    this.ttlMs = ttlMs;
    this.maxSize = maxSize;
    this.sweepIntervalMs = sweepIntervalMs;
  }

  set(fingerprint: string, session: SessionMeta): void {
    const now = Date.now();
    this.maybeCleanup(now);

    const existing = this.getLiveEntry(fingerprint, now);
    this.entries.set(fingerprint, mergeSessionMeta(existing, session)!);
    this.evictOldest();
  }

  get(fingerprint: string): SessionMeta | null {
    const now = Date.now();
    this.maybeCleanup(now);

    return this.getLiveEntry(fingerprint, now);
  }

  get size(): number {
    return this.entries.size;
  }

  sweep(now = Date.now()): void {
    this.cleanup(now);
  }

  private maybeCleanup(now = Date.now()): void {
    if (now - this.lastSweepAt < this.sweepIntervalMs) {
      return;
    }
    this.cleanup(now);
  }

  private cleanup(now = Date.now()): void {
    this.lastSweepAt = now;
    for (const [fingerprint, session] of this.entries) {
      if (this.isExpired(session, now)) {
        this.entries.delete(fingerprint);
      }
    }
  }

  private getLiveEntry(fingerprint: string, now: number): SessionMeta | null {
    const entry = this.entries.get(fingerprint) ?? null;
    if (entry == null) {
      return null;
    }
    if (!this.isExpired(entry, now)) {
      return entry;
    }

    this.entries.delete(fingerprint);
    return null;
  }

  private isExpired(session: SessionMeta, now: number): boolean {
    return now - session.createdAt > this.ttlMs;
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
