# Session Chain Graft Production Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Discovery 단계에서 검증 완료된 b6 fingerprint -> bh.c.t -> bh.c.A injection 경로를 정식 thread-image-graft.ts로 승격하고, Go bot의 BANNED_ROOM_IDS room blocking을 IMAGE_THREAD_GRAFT_READY readiness 기반으로 교체하며, frida/ 디렉터리를 canonical TS+Go 레이아웃으로 전환한다.

**Architecture:** Iris Frida agent에서 4-hook 체인 (b6 capture -> bh.c.o/t session restore -> bh.c.A inject -> u() cleanup)으로 image thread graft를 수행한다. Go graft-daemon이 TS agent를 frida-compile로 번들링하고 KakaoTalk 프로세스에 attach/lifecycle을 관리한다. Go bot에서는 room ID 기반 차단을 제거하고, boolean feature flag 기반 graft readiness gate로 교체한다.

**Tech Stack:** TypeScript (frida-compile, Node.js test runner), Go 1.26.x (golangci-lint v2, testify, frida-go)

**Spec references:**
- 설계: `/home/kapu/gemini/Iris/docs/superpowers/specs/2026-03-25-session-chain-graft-design.md`
- 실측 검증: `/home/kapu/gemini/Iris/docs/superpowers/specs/2026-03-25-session-chain-graft-live-validation.md`
- Discovery 코드 원본: `/home/kapu/gemini/Iris/frida-modern/agent/thread-image-discovery.ts`

---

## File Map

### Part A: Iris Frida Agent (`/home/kapu/gemini/Iris/frida-modern/agent/`)

| Action | File | Responsibility |
|--------|------|----------------|
| CREATE | `shared/fingerprint-store.ts` | FingerprintSessionStore class, fingerprintUriStrings(), mergeSessionMeta() |
| CREATE | `test/fingerprint-store.test.ts` | store/get/TTL/merge/eviction/downgrade-protection tests |
| MODIFY | `shared/kakao.ts` | Add BASE_INTENT_FILTER_TARGET, CHAT_MEDIA_SENDER_TARGET constants |
| REWRITE | `thread-image-graft.ts` | 4-hook chain: b6 -> bh.c.o -> bh.c.A -> u() |
| REWRITE | `test/image-flow.test.ts` | Update for new planImageGraft() API (drop hint-file tests) |

### Part B: Go Bot (`/home/kapu/gemini/chat-bot-go-kakao/`)

| Action | File | Responsibility |
|--------|------|----------------|
| MODIFY | `internal/config/config.go` | Remove BannedRoomIDs, add ImageThreadGraftReady |
| MODIFY | `internal/config/load_sections.go` | Replace BANNED_ROOM_IDS with IMAGE_THREAD_GRAFT_READY |
| MODIFY | `internal/bot/service.go` | Remove bannedRoomIDs field/method, add imageThreadGraftReady + gate method |
| MODIFY | `internal/bot/draw_handler.go` | Add graft readiness gate before image send |
| MODIFY | `internal/app/app.go` | Wire ImageThreadGraftReady |

### Part C: Directory Transition + Go Daemon (`/home/kapu/gemini/Iris/`)

| Action | File | Responsibility |
|--------|------|----------------|
| MOVE | `frida/graft-daemon.py` -> `frida/legacy/` | Legacy Python daemon backup |
| MOVE | `frida/thread-image-graft.js` -> `frida/legacy/` | Legacy JS agent backup |
| MOVE | `frida/thread-markdown-graft.js` -> `frida/legacy/` | Legacy JS agent backup |
| MOVE | `frida-modern/agent/*` -> `frida/agent/` | Canonical TS agent location |
| MOVE | `frida-modern/daemon/*` -> `frida/daemon/` | Canonical Go daemon location (module path: `github.com/park285/Iris/frida/daemon`) |
| VERIFY | `frida/daemon/` | Go build succeeds |
| VERIFY | `frida/agent/` | TS tests pass, frida-compile bundle succeeds |

---

## Part A: Iris Frida Agent

### Task 1: FingerprintSessionStore module

**Files:**
- Create: `shared/fingerprint-store.ts`
- Test: `test/fingerprint-store.test.ts`

- [ ] **Step 1: Write tests for fingerprint store**

```typescript
// test/fingerprint-store.test.ts
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  FingerprintSessionStore,
  fingerprintUriStrings,
  mergeSessionMeta,
} from '../shared/fingerprint-store.js';
import { type SessionMeta } from '../shared/session.js';

const session = (overrides: Partial<SessionMeta> = {}): SessionMeta => ({
  sessionId: 'sess-1',
  threadId: '3804041011037167620',
  threadScope: 2,
  roomId: '18478615493603057',
  createdAt: Date.now(),
  ...overrides,
});

test('fingerprintUriStrings returns "empty" for empty array', () => {
  assert.equal(fingerprintUriStrings([]), 'empty');
});

test('fingerprintUriStrings joins count and URIs', () => {
  assert.equal(
    fingerprintUriStrings(['content://a', 'content://b']),
    '2:content://a|content://b',
  );
});

test('store.get returns null for unknown fingerprint', () => {
  const store = new FingerprintSessionStore();
  assert.equal(store.get('unknown'), null);
});

test('store round-trips a session by fingerprint', () => {
  const store = new FingerprintSessionStore();
  const s = session();
  store.set('fp1', s);
  assert.deepEqual(store.get('fp1'), s);
});

test('store evicts entries beyond TTL', () => {
  const store = new FingerprintSessionStore(100);
  store.set('fp1', session({ createdAt: Date.now() - 200 }));
  assert.equal(store.get('fp1'), null);
});

test('store evicts oldest entries beyond maxSize', () => {
  const store = new FingerprintSessionStore(60_000, 2);
  store.set('fp1', session({ sessionId: 's1' }));
  store.set('fp2', session({ sessionId: 's2' }));
  store.set('fp3', session({ sessionId: 's3' }));
  assert.equal(store.get('fp1'), null);
  assert.notEqual(store.get('fp3'), null);
});

test('mergeSessionMeta prefers previous sessionId, fills missing threadId from next', () => {
  const prev = session({ threadId: null });
  const next = session({ sessionId: 'other', threadId: '999' });
  const merged = mergeSessionMeta(prev, next)!;
  assert.equal(merged.sessionId, prev.sessionId);
  assert.equal(merged.threadId, '999');
});

test('mergeSessionMeta does not downgrade threadScope', () => {
  const prev = session({ threadScope: 2 });
  const next = session({ threadScope: 0 });
  const merged = mergeSessionMeta(prev, next)!;
  assert.equal(merged.threadScope, 2);
});

test('store.set merges with existing session for same fingerprint', () => {
  const store = new FingerprintSessionStore();
  store.set('fp1', session({ threadId: null, threadScope: 0 }));
  store.set('fp1', session({ sessionId: 'other', threadId: '999', threadScope: 2 }));
  const result = store.get('fp1')!;
  assert.equal(result.threadId, '999');
  assert.equal(result.threadScope, 2);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/kapu/gemini/Iris/frida-modern/agent && npx tsx --test test/fingerprint-store.test.ts`
Expected: FAIL (module not found)

- [ ] **Step 3: Implement FingerprintSessionStore**

```typescript
// shared/fingerprint-store.ts
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

  return {
    sessionId: previous.sessionId,
    threadId: previous.threadId ?? next.threadId,
    threadScope: previous.threadScope > 0 ? previous.threadScope : next.threadScope,
    roomId: previous.roomId ?? next.roomId,
    createdAt: Math.min(prevTime, nextTime) === Infinity ? Date.now() : Math.min(prevTime, nextTime),
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
    for (const [fp, session] of this.entries) {
      if (now - session.createdAt > this.ttlMs) {
        this.entries.delete(fp);
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/kapu/gemini/Iris/frida-modern/agent && npx tsx --test test/fingerprint-store.test.ts`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
cd /home/kapu/gemini/Iris && git add frida-modern/agent/shared/fingerprint-store.ts frida-modern/agent/test/fingerprint-store.test.ts
git commit -m "feat(agent): add FingerprintSessionStore for URI-based session correlation"
```

---

### Task 2: Update hook targets in shared/kakao.ts

**Files:**
- Modify: `shared/kakao.ts`

- [ ] **Step 1: Add new hook target constants**

Add after existing constants in `shared/kakao.ts`:

```typescript
export const BASE_INTENT_FILTER_TARGET = {
  className: 'com.kakao.talk.activity.f',
  captureMethod: 'b6',
  bridgeMethod: 'Z5',
} as const;

export const CHAT_MEDIA_SENDER_TARGET = {
  className: 'bh.c',
  entryMethod: 'o',
  processMethod: 't',
  injectMethod: 'A',
} as const;
```

- [ ] **Step 2: Typecheck**

Run: `cd /home/kapu/gemini/Iris/frida-modern/agent && npx tsc --noEmit`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
cd /home/kapu/gemini/Iris && git add frida-modern/agent/shared/kakao.ts
git commit -m "feat(agent): add BaseIntentFilter and ChatMediaSender hook targets"
```

---

### Task 3: Rewrite thread-image-graft.ts (pure logic + types)

**Files:**
- Rewrite: `thread-image-graft.ts`
- Rewrite: `test/image-flow.test.ts`

- [ ] **Step 1: Write tests for new planImageGraft() function**

Replace `test/image-flow.test.ts` entirely:

```typescript
// test/image-flow.test.ts
import test from 'node:test';
import assert from 'node:assert/strict';
import type { SessionMeta } from '../shared/session.js';

const session = (overrides: Partial<SessionMeta> = {}): SessionMeta => ({
  sessionId: 'sess-1',
  threadId: '3804041011037167620',
  threadScope: 2,
  roomId: '18478615493603057',
  createdAt: Date.now(),
  ...overrides,
});

test('planImageGraft skips when session is null', async () => {
  const { planImageGraft } = await import('../thread-image-graft.js');
  const plan = planImageGraft(null, '18478615493603057');
  assert.equal(plan.status, 'skip-no-session');
  assert.equal(plan.injection, null);
});

test('planImageGraft skips when session has no threadId', async () => {
  const { planImageGraft } = await import('../thread-image-graft.js');
  const plan = planImageGraft(session({ threadId: null, threadScope: 0 }), '18478615493603057');
  assert.equal(plan.status, 'skip-no-thread');
  assert.equal(plan.injection, null);
});

test('planImageGraft skips when threadScope < 2', async () => {
  const { planImageGraft } = await import('../thread-image-graft.js');
  const plan = planImageGraft(session({ threadScope: 1 }), '18478615493603057');
  assert.equal(plan.status, 'skip-no-thread');
  assert.equal(plan.injection, null);
});

test('planImageGraft skips when roomId does not match', async () => {
  const { planImageGraft } = await import('../thread-image-graft.js');
  const plan = planImageGraft(session(), 'different-room');
  assert.equal(plan.status, 'skip-room-mismatch');
  assert.equal(plan.injection, null);
});

test('planImageGraft skips when session roomId is null (fail-closed)', async () => {
  const { planImageGraft } = await import('../thread-image-graft.js');
  const plan = planImageGraft(session({ roomId: null }), '18478615493603057');
  assert.equal(plan.status, 'skip-room-mismatch');
  assert.equal(plan.injection, null);
});

test('planImageGraft injects when session and room match with scope >= 2', async () => {
  const { planImageGraft } = await import('../thread-image-graft.js');
  const s = session();
  const plan = planImageGraft(s, s.roomId!);
  assert.equal(plan.status, 'inject');
  assert.deepEqual(plan.injection, {
    threadId: s.threadId,
    threadScope: s.threadScope,
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/kapu/gemini/Iris/frida-modern/agent && npx tsx --test test/image-flow.test.ts`
Expected: FAIL (planImageGraft not exported / different API)

- [ ] **Step 3: Rewrite thread-image-graft.ts**

Complete rewrite. Key exported symbols:
- `planImageGraft(session, roomId)` — pure decision logic
- `installThreadImageGraft(runtime?)` — 4-hook installer

Source: Extract and clean from `thread-image-discovery.ts`. Reference the discovery code directly for:
- `readIntentIdentifier`, `readIntentStringExtra`, `readIntentIntExtra`, `readIntentLongExtra` — copy from discovery L119-278
- `snapshotIntent`, `applySessionExtras` — copy from discovery L167-206
- `readChatRoomId`, `readAttachmentText`, `rewriteAttachmentText` — copy from discovery L368-455
- `fingerprintUris` (Java List wrapper) — copy from discovery L310-337

New structure:

```typescript
// thread-image-graft.ts
import { readSessionMeta, type SessionMeta, type IntentSnapshot } from './shared/session.js';
import {
  FingerprintSessionStore,
  fingerprintUriStrings,
} from './shared/fingerprint-store.js';
import {
  BASE_INTENT_FILTER_TARGET,
  CHAT_MEDIA_SENDER_TARGET,
  KAKAO_IMAGE_GRAFT_TARGET,
} from './shared/kakao.js';
import { isPhotoMessageType, removeCallingPkgFromAttachment } from './shared/message.js';

// --- Types ---

type RuntimeLike = {
  Java?: {
    available?: boolean;
    perform: (callback: () => void) => void;
    use: (className: string) => any;
  };
  send?: (message: string) => void;
};

export type ImageGraftPlan = {
  status: 'inject' | 'skip-no-session' | 'skip-room-mismatch' | 'skip-no-thread';
  injection: { threadId: string; threadScope: number } | null;
};

// --- Module state ---

const store = new FingerprintSessionStore();
let currentMediaSenderSession: SessionMeta | null = null;

// --- Pure logic (exported for testing) ---

export function planImageGraft(
  session: SessionMeta | null,
  roomId: string | null,
): ImageGraftPlan {
  if (session == null) {
    return { status: 'skip-no-session', injection: null };
  }

  if (session.threadId == null || session.threadScope < 2) {
    return { status: 'skip-no-thread', injection: null };
  }

  if (session.roomId == null || roomId == null || session.roomId !== roomId) {
    return { status: 'skip-room-mismatch', injection: null };
  }

  return {
    status: 'inject',
    injection: { threadId: session.threadId, threadScope: session.threadScope },
  };
}

// --- Intent helpers (Frida JNI) ---
// (readIntentIdentifier, readIntentStringExtra, readIntentIntExtra,
//  readIntentLongExtra, snapshotIntent, applySessionExtras)
// Copy verbatim from thread-image-discovery.ts L119-206

// --- SendingLog helpers (Frida JNI) ---
// (readChatRoomId, readAttachmentText, rewriteAttachmentText)
// Copy verbatim from thread-image-discovery.ts L368-455

// --- URI fingerprint bridge ---
// (fingerprintUris: Java List -> string[] -> fingerprintUriStrings)
// Copy from thread-image-discovery.ts L310-337

// --- Hook A: BaseIntentFilterActivity.b6() ---

function installB6Hook(runtime: RuntimeLike): void {
  const J = runtime.Java!;
  const Activity = J.use(BASE_INTENT_FILTER_TARGET.className);

  for (const overload of Activity[BASE_INTENT_FILTER_TARGET.captureMethod].overloads) {
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
        store.set(fingerprint, merged);
        runtime.send?.(`[graft] b6 captured fp=${fingerprint.slice(0, 80)} session=${merged.sessionId} thread=${merged.threadId ?? 'none'} scope=${merged.threadScope}`);
      }

      // Bridge extras to sub-intents for downstream visibility.
      applySessionExtras(runtime, outerIntent, activityIntent, 'b6.outer');
      applySessionExtras(runtime, nestedIntent, activityIntent, 'b6.nested');

      return overload.apply(this, args);
    };
  }
}

// --- Hook B: bh.c.o() — media sender entry ---

function installMediaSenderHooks(runtime: RuntimeLike): void {
  const J = runtime.Java!;
  const Sender = J.use(CHAT_MEDIA_SENDER_TARGET.className);

  // o() — entry, scopes currentMediaSenderSession to entire call chain
  for (const overload of Sender[CHAT_MEDIA_SENDER_TARGET.entryMethod].overloads) {
    overload.implementation = function (...args: any[]) {
      const uriList = args[0];
      const fingerprint = fingerprintUris(uriList);
      currentMediaSenderSession = store.get(fingerprint);

      try {
        return overload.apply(this, args);
      } finally {
        currentMediaSenderSession = null;
      }
    };
  }

  // t() — fallback restore (safety net if o() missed the fingerprint)
  for (const overload of Sender[CHAT_MEDIA_SENDER_TARGET.processMethod].overloads) {
    overload.implementation = function (...args: any[]) {
      if (currentMediaSenderSession == null) {
        const uriList = args[9];
        const fingerprint = fingerprintUris(uriList);
        currentMediaSenderSession = store.get(fingerprint);
      }

      return overload.apply(this, args);
    };
  }

  // A() — injection point
  for (const overload of Sender[CHAT_MEDIA_SENDER_TARGET.injectMethod].overloads) {
    overload.implementation = function (...args: any[]) {
      const sendingLog = args[0];
      const session = currentMediaSenderSession;

      if (session != null) {
        const roomId = readChatRoomId(runtime, sendingLog);
        const plan = planImageGraft(session, roomId);

        if (plan.status === 'inject' && plan.injection != null) {
          try {
            sendingLog.H1(plan.injection.threadScope);
            sendingLog.J1(
              J.use('java.lang.Long').valueOf(
                J.use('java.lang.String').valueOf(plan.injection.threadId),
              ),
            );
            runtime.send?.(`[graft] A injected session=${session.sessionId} room=${roomId} threadId=${plan.injection.threadId} scope=${plan.injection.threadScope}`);
          } catch (error) {
            runtime.send?.(`[graft] A injection-failed session=${session.sessionId} error=${String(error)}`);
          }
        } else {
          runtime.send?.(`[graft] A skip status=${plan.status} session=${session.sessionId}`);
        }
      }

      return overload.apply(this, args);
    };
  }
}

// --- Hook C: ChatSendingLogRequest$a.u() — callingPkg cleanup + observe ---

function installSendingLogHook(runtime: RuntimeLike): void {
  const J = runtime.Java!;
  const Companion = J.use(KAKAO_IMAGE_GRAFT_TARGET.className);

  for (const overload of Companion[KAKAO_IMAGE_GRAFT_TARGET.methodName].overloads) {
    overload.implementation = function (...args: any[]) {
      const sendingLog = args[1];
      const messageType = readMessageType(sendingLog);

      if (isPhotoMessageType(messageType)) {
        const attachmentText = readAttachmentText(sendingLog);
        const cleaned = removeCallingPkgFromAttachment(attachmentText);
        if (cleaned != null && cleaned !== attachmentText) {
          rewriteAttachmentText(sendingLog, cleaned);
        }
      }

      return overload.apply(this, args);
    };
  }
}

// --- Main installer ---

export function installThreadImageGraft(
  runtime: RuntimeLike = globalThis as RuntimeLike,
): boolean {
  const J = runtime.Java;
  if (J == null || typeof J.perform !== 'function') {
    return false;
  }

  J.perform(() => {
    installB6Hook(runtime);
    installMediaSenderHooks(runtime);
    installSendingLogHook(runtime);
    runtime.send?.('[graft] thread-image-graft hooks installed (session-chain mode)');
  });

  return true;
}

if ((globalThis as RuntimeLike).Java?.available) {
  installThreadImageGraft();
}
```

Key implementation notes:
- `mergeSessionMeta`은 `shared/fingerprint-store.ts`에서 import (Task 1에서 생성)
- `readMessageType`은 `sendingLog.w0().toString()`으로 읽음 (discovery L360-366 참조)
- intent helper 함수들은 discovery.ts에서 복사. 단, discovery-only logging은 제거
- `H1(scope)`, `J1(threadId)` setter는 live 검증에서 확인된 public setter method
- 기존 `planImageInjection`, `ImageInjectionPlan`, hint file 관련 코드는 전부 제거

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/kapu/gemini/Iris/frida-modern/agent && npx tsx --test test/image-flow.test.ts`
Expected: All tests PASS

- [ ] **Step 5: Typecheck**

Run: `cd /home/kapu/gemini/Iris/frida-modern/agent && npx tsc --noEmit`
Expected: No errors

- [ ] **Step 6: Run all tests**

Run: `cd /home/kapu/gemini/Iris/frida-modern/agent && npx tsx --test`
Expected: All tests PASS (session, fingerprint-store, image-flow, markdown-flow, hint, message)

- [ ] **Step 7: Commit**

```bash
cd /home/kapu/gemini/Iris && git add frida-modern/agent/thread-image-graft.ts frida-modern/agent/test/image-flow.test.ts
git commit -m "feat(agent): rewrite thread-image-graft with session-chain 4-hook architecture

Replace token-based hint file mechanism with URI fingerprint session chain.
Hooks: b6 (capture) -> bh.c.o (scope) -> bh.c.A (inject) -> u (cleanup).
Validated live: threadId=3804041011037167620 scope=2 confirmed at u.observe."
```

---

## Part B: Go Bot

### Task 4: Replace BANNED_ROOM_IDS config with IMAGE_THREAD_GRAFT_READY

**Files:**
- Modify: `internal/config/config.go`
- Modify: `internal/config/load_sections.go`

- [ ] **Step 1: Update config.go**

In `internal/config/config.go`, replace `BannedRoomIDs` field:

```go
// Remove:
BannedRoomIDs        []string

// Add:
ImageThreadGraftReady bool
```

- [ ] **Step 2: Update load_sections.go**

Replace:
```go
cfg.BannedRoomIDs = envList("BANNED_ROOM_IDS")
```

With:
```go
cfg.ImageThreadGraftReady = envBool("IMAGE_THREAD_GRAFT_READY", false)
```

- [ ] **Step 3: Verify compilation**

Run: `cd /home/kapu/gemini/chat-bot-go-kakao && go build ./...`
Expected: Compilation errors in `app.go` and `service.go` (referencing removed field). These will be fixed in the next task.

---

### Task 5: Replace room blocking with graft readiness gate in bot service

**Files:**
- Modify: `internal/bot/service.go`
- Modify: `internal/bot/draw_handler.go`
- Modify: `internal/app/app.go`

- [ ] **Step 1: Update Service struct and Options in service.go**

In `Options` struct, replace:
```go
// Remove:
BannedRoomIDs     []string

// Add:
ImageThreadGraftReady bool
```

In `Service` struct, replace:
```go
// Remove:
bannedRoomIDs     map[string]struct{}

// Add:
imageThreadGraftReady bool
```

In `New()` constructor, replace:
```go
// Remove:
bannedRoomIDs:     normalizeUserIDSet(opts.BannedRoomIDs),

// Add:
imageThreadGraftReady: opts.ImageThreadGraftReady,
```

Replace `isBannedRoom` and `rejectIfBanned`:
```go
// Remove isBannedRoom() entirely.

// Update rejectIfBanned to only check banned users:
func (s *Service) rejectIfBanned(meta *messageContext) bool {
	if !s.isBannedUser(meta) {
		return false
	}

	s.observeIgnoredCommand()
	s.logBannedUser(meta)

	return true
}

// Add new method:
func (s *Service) skipThreadedImageIfGraftNotReady(meta *messageContext) bool {
	if s.imageThreadGraftReady {
		return false
	}

	if meta.threadID == "" {
		return false
	}

	if s.logger != nil {
		s.logger.Info(
			"draw_skipped_graft_not_ready",
			slog.String("room", meta.roomID),
			slog.String("thread_id", meta.threadID),
		)
	}

	return true
}
```

- [ ] **Step 2: Add graft gate to draw_handler.go**

In `handleDrawImageCommand`, add check after banned check:

```go
func (s *Service) handleDrawImageCommand(ctx context.Context, meta *messageContext, parsed ParsedCommand) {
	if s.rejectIfBanned(meta) {
		return
	}

	if s.skipThreadedImageIfGraftNotReady(meta) {
		return
	}
    // ... rest unchanged
```

- [ ] **Step 3: Update app.go wiring**

Replace:
```go
BannedRoomIDs:     append([]string(nil), cfg.BannedRoomIDs...),
```

With:
```go
ImageThreadGraftReady: cfg.ImageThreadGraftReady,
```

- [ ] **Step 4: Build**

Run: `cd /home/kapu/gemini/chat-bot-go-kakao && go build ./...`
Expected: Compilation succeeds

- [ ] **Step 5: Run tests**

Run: `cd /home/kapu/gemini/chat-bot-go-kakao && go test ./internal/bot/... ./internal/config/... ./internal/app/...`
Expected: PASS. Existing tests referencing BannedRoomIDs may need updates (check for compilation errors and fix any test that sets BannedRoomIDs in Options).

- [ ] **Step 6: Run lint**

Run: `cd /home/kapu/gemini/chat-bot-go-kakao && golangci-lint run`
Expected: PASS (or only pre-existing warnings)

- [ ] **Step 7: Commit**

```bash
cd /home/kapu/gemini/chat-bot-go-kakao
git add internal/config/config.go internal/config/load_sections.go internal/bot/service.go internal/bot/draw_handler.go internal/app/app.go
git commit -m "feat(bot): replace BANNED_ROOM_IDS with IMAGE_THREAD_GRAFT_READY

Room-based blocking was a temporary workaround for missing image thread
graft. Now that session-chain graft is validated (b6 -> bh.c.A path),
replace with a boolean readiness gate.

- Remove: BANNED_ROOM_IDS env var, bannedRoomIDs map, isBannedRoom()
- Add: IMAGE_THREAD_GRAFT_READY env var (default: false)
- Gate: threaded image draw is skipped when graft not ready (fail-closed)
- Non-threaded image draw and all text commands are unaffected"
```

---

## Part C: Directory Transition + Go Daemon

### Task 6: Canonical directory reorganization

**Files:**
- Move: legacy files to `frida/legacy/`
- Move: `frida-modern/agent/` -> `frida/agent/`
- Move: `frida-modern/daemon/` -> `frida/daemon/`

Rationale: Go daemon의 module path는 `github.com/park285/Iris/frida/daemon`이고, `ParseConfig`는 agent를 `<repo>/frida/agent/` 기준으로 resolve한다. `frida-modern/`은 개발 staging으로, 전환 완료 시 canonical `frida/` 레이아웃으로 이동해야 한다.

- [ ] **Step 1: Create legacy backup directory and move legacy files**

```bash
cd /home/kapu/gemini/Iris
mkdir -p frida/legacy
git mv frida/graft-daemon.py frida/legacy/
git mv frida/thread-image-graft.js frida/legacy/
git mv frida/thread-markdown-graft.js frida/legacy/
```

- [ ] **Step 2: Preflight check — verify canonical paths are clean**

```bash
cd /home/kapu/gemini/Iris
# canonical 경로에 기존 파일이 없는지 확인. 있으면 worktree 잔재 → 수동 정리 필요
ls frida/agent/ 2>/dev/null && echo "WARNING: frida/agent/ already exists — investigate before proceeding" || echo "OK: frida/agent/ is clean"
ls frida/daemon/ 2>/dev/null && echo "WARNING: frida/daemon/ already exists — investigate before proceeding" || echo "OK: frida/daemon/ is clean"
```

Expected: 둘 다 "OK: clean". WARNING이 뜨면 기존 내용을 확인하고 수동으로 정리한 뒤 진행.

- [ ] **Step 3: Move TS agent to canonical location**

```bash
cd /home/kapu/gemini/Iris
cp -r frida-modern/agent frida/agent
git add frida/agent/
```

- [ ] **Step 4: Move Go daemon to canonical location**

```bash
cd /home/kapu/gemini/Iris
cp -r frida-modern/daemon frida/daemon
git add frida/daemon/
```

- [ ] **Step 5: Verify agent tests from new location**

Run: `cd /home/kapu/gemini/Iris/frida/agent && npm install && npx tsx --test`
Expected: All tests PASS

- [ ] **Step 6: Verify Go daemon builds**

Run: `cd /home/kapu/gemini/Iris/frida/daemon && go build ./...`
Expected: Build succeeds (stub mode, no `frida_core` tag)

- [ ] **Step 7: Verify agent bundle**

Run: `cd /home/kapu/gemini/Iris/frida/agent && npx frida-compile thread-image-graft.ts -o /tmp/test-bundle.js && wc -c /tmp/test-bundle.js`
Expected: Bundle generated, non-zero size. Confirms frida-compile resolves all imports (shared/fingerprint-store, shared/session, shared/kakao, shared/message).

- [ ] **Step 8: Commit**

```bash
cd /home/kapu/gemini/Iris
git add frida/legacy/ frida/agent/ frida/daemon/
git commit -m "refactor: migrate frida/ to canonical TS+Go layout

- Legacy Python daemon and JS agents moved to frida/legacy/
- TS agent (session-chain 4-hook) at frida/agent/
- Go graft-daemon at frida/daemon/ (module: github.com/park285/Iris/frida/daemon)
- frida-modern/ was development staging, canonical location is now frida/"
```

---

### Task 7: Go daemon integration verification

**Files:**
- Verify: `frida/daemon/` (no code changes expected)

이 태스크는 daemon이 새 agent를 정상적으로 bundle + attach할 수 있는지 확인한다.

- [ ] **Step 1: Verify daemon CLI invocation pattern**

daemon은 `--agent thread-image-graft.ts`로 실행하며, `ParseConfig`가 `<repo>/frida/agent/thread-image-graft.ts`로 resolve한다.

```bash
cd /home/kapu/gemini/Iris/frida/daemon
# Dry-run: config parsing만 확인 (실 attach는 device 필요)
go run ./cmd/graft-daemon --agent thread-image-graft.ts --device test 2>&1 || true
```

Expected: "pid lookup failed" 또는 유사한 runtime 에러 (config parsing은 성공). `agent is required` 에러가 나면 안 됨.

- [ ] **Step 2: Verify CLIBundler works standalone**

```bash
cd /home/kapu/gemini/Iris/frida/agent
npx frida-compile thread-image-graft.ts -o /tmp/graft-bundle.js
# bundle에 fingerprint-store, session, kakao, message 코드가 포함됐는지 확인
grep -c 'FingerprintSessionStore\|fingerprintUriStrings\|planImageGraft\|installThreadImageGraft' /tmp/graft-bundle.js
```

Expected: 4개 이상 매칭 (모든 주요 symbol이 bundle에 포함)

- [ ] **Step 3: Verify markdown graft also bundles**

```bash
cd /home/kapu/gemini/Iris/frida/agent
npx frida-compile thread-markdown-graft.ts -o /tmp/markdown-bundle.js
grep -c 'installThreadMarkdownGraftHook' /tmp/markdown-bundle.js
```

Expected: 1개 이상 매칭

- [ ] **Step 4: Document daemon run command for deployment**

Production 실행 명령:
```bash
cd /home/kapu/gemini/Iris
./frida/daemon/graft-daemon \
  --agent thread-image-graft.ts \
  --device <ADB_DEVICE_ID> \
  --pid-poll-interval 30 \
  --retry-delay 5
```

`frida_core` build tag로 빌드된 바이너리 (`graft-daemon-real`)를 사용하거나, stub 빌드 시 CLI fallback (`npm exec frida-compile`)이 동작한다.

Legacy rollback:
```bash
python3 frida/legacy/graft-daemon.py --script frida/legacy/thread-image-graft.js
```

---

## Deployment order

1. **Iris directory transition** (Part C Task 6): canonical layout으로 정리
2. **Iris agent** (Part A): session-chain 4-hook agent 배포, live에서 `[graft] thread-image-graft hooks installed (session-chain mode)` 확인
3. **Go daemon** (Part C Task 7): Python daemon → Go daemon 전환, PID reconcile 정상 동작 확인
4. **Go bot** (Part B): `IMAGE_THREAD_GRAFT_READY=true` 설정 후 배포
5. **검증**: threaded room에서 `!그림` 명령 → 이미지가 thread 내부에 도착하는지 확인
6. **정리**: `BANNED_ROOM_IDS` 환경변수 .env에서 제거, `frida-modern/` 디렉터리 정리
