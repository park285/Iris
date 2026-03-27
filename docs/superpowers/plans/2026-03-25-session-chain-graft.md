# Session Chain Graft Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Iris image thread graft를 hint file 기반에서 session chain (intent extras + Frida 3-hook) 기반으로 전환하여 same-room different-thread 동시 전송의 race condition을 제거한다.

**Architecture:** Iris가 image send intent에 session extras(`sessionId`, `threadId`, `threadScope`, `roomId`)를 삽입하면, Frida TS agent가 3-hook 구조(C0694y 생성자 → C0694y.b() → u())로 extras를 읽어 thread-local을 통해 ChatSendingLog에 thread 정보를 주입한다. Frida V8의 hook 직렬화 특성으로 동기 호출 체인 내에서 module-level 변수가 thread-local로 안전하게 동작한다.

**Tech Stack:** Kotlin (Android app_process), TypeScript (Frida agent, tsx test runner), Go (graft daemon), Frida Java Bridge

**Spec:** `docs/superpowers/specs/2026-03-25-session-chain-graft-design.md`

---

## File Structure

### 신규 생성

| File | Responsibility |
|------|---------------|
| `frida/agent/shared/session.ts` | SessionStore (TTL+size-capped Map), ThreadLocal (module-level 변수), SessionMeta 타입 |
| `frida/agent/shared/log.ts` | `send()` 기반 구조화 로그 helper |
| `frida/agent/test/session.test.ts` | SessionStore/ThreadLocal 단위 테스트 |

### 수정

| File | Change |
|------|--------|
| `frida/agent/shared/kakao.ts` | HookSpec 타입 + ConnectionOsStream 상수 + IRIS_EXTRAS 상수 추가 |
| `frida/agent/shared/message.ts` | token 관련 함수 제거 (`extractPathCandidatesFromAttachment`, `collectStringCandidates`). callingPkg 제거 유지 |
| `frida/agent/thread-image-graft.ts` | 전체 재작성: token claim → 3-hook session chain + discovery mode |
| `frida/agent/test/image-flow.test.ts` | session chain 데이터 흐름 테스트로 재작성 |
| `frida/agent/test/message.test.ts` | token 관련 테스트 케이스 제거 |
| `app/src/main/java/party/qwer/iris/ReplyService.kt` | PreparedImages 필드 추가, intent session extras 삽입, writeThreadHint 환경변수 게이트 |
| `frida/daemon/internal/app/config.go` | `--discovery`, `--hook-spec` 플래그 추가 |
| `frida/daemon/internal/app/config_test.go` | 새 플래그 테스트 추가 |

### 삭제

| File | Reason |
|------|--------|
| `frida/agent/shared/hint.ts` | session chain이 hint file 역할을 대체 |
| `frida/agent/test/hint.test.ts` | hint.ts 삭제에 따른 정리 |

---

## Worktree 참고

기존 `.worktrees/frida-ts-go-graft/` worktree에 TS/Go 인프라(package.json, tsconfig.json, Go modules, legacy/ 백업)가 구축되어 있다. Token 기반 agent 코드를 session chain으로 교체한다. Kotlin 변경은 main worktree에서 수행한다.

---

### Task 1: shared/session.ts 테스트 작성

**Files:**
- Create: `frida/agent/test/session.test.ts`

- [ ] **Step 1: 테스트 파일 작성**

```typescript
import test from 'node:test';
import assert from 'node:assert/strict';
import { SessionStore, threadLocal, type SessionMeta } from '../shared/session.js';

const makeMeta = (overrides: Partial<SessionMeta> = {}): SessionMeta => ({
  sessionId: 'test-session',
  threadId: '3803466729815130113',
  threadScope: 2,
  roomId: '18476130232878491',
  createdAt: Date.now(),
  ...overrides,
});

test('SessionStore: set and get', () => {
  const store = new SessionStore();
  const meta = makeMeta();
  store.set('k1', meta);
  assert.deepEqual(store.get('k1'), meta);
});

test('SessionStore: get returns null for missing key', () => {
  const store = new SessionStore();
  assert.equal(store.get('missing'), null);
});

test('SessionStore: TTL expiry', () => {
  const store = new SessionStore(32, 100);
  const meta = makeMeta({ createdAt: Date.now() - 200 });
  store.set('k1', meta);
  assert.equal(store.get('k1'), null);
});

test('SessionStore: max size eviction removes oldest', () => {
  const store = new SessionStore(2);
  store.set('k1', makeMeta({ sessionId: 's1' }));
  store.set('k2', makeMeta({ sessionId: 's2' }));
  store.set('k3', makeMeta({ sessionId: 's3' }));
  assert.equal(store.get('k1'), null);
  assert.notEqual(store.get('k2'), null);
  assert.notEqual(store.get('k3'), null);
});

test('SessionStore: compositeKey format', () => {
  assert.equal(SessionStore.compositeKey(12345, 'room1'), '12345:room1');
});

test('SessionStore: delete returns true for existing key', () => {
  const store = new SessionStore();
  store.set('k1', makeMeta());
  assert.equal(store.delete('k1'), true);
  assert.equal(store.get('k1'), null);
});

test('SessionStore: delete returns false for missing key', () => {
  const store = new SessionStore();
  assert.equal(store.delete('missing'), false);
});

test('SessionStore: overwrite existing key', () => {
  const store = new SessionStore();
  store.set('k1', makeMeta({ sessionId: 'old' }));
  store.set('k1', makeMeta({ sessionId: 'new' }));
  assert.equal(store.get('k1')?.sessionId, 'new');
  assert.equal(store.size, 1);
});

test('threadLocal: starts null', () => {
  threadLocal.remove();
  assert.equal(threadLocal.get(), null);
});

test('threadLocal: set/get/remove cycle', () => {
  const meta = makeMeta();
  threadLocal.set(meta);
  assert.deepEqual(threadLocal.get(), meta);
  threadLocal.remove();
  assert.equal(threadLocal.get(), null);
});

test('threadLocal: remove is idempotent', () => {
  threadLocal.remove();
  threadLocal.remove();
  assert.equal(threadLocal.get(), null);
});
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd frida/agent && npx tsx --test test/session.test.ts`
Expected: FAIL — `shared/session.js` 모듈 미존재

---

### Task 2: shared/session.ts 구현

**Files:**
- Create: `frida/agent/shared/session.ts`

- [ ] **Step 1: SessionStore + ThreadLocal + SessionMeta 구현**

```typescript
export type SessionMeta = {
  sessionId: string;
  threadId: string;
  threadScope: number;
  roomId: string;
  createdAt: number;
};

export class SessionStore {
  private readonly store = new Map<string, SessionMeta>();
  private readonly maxSize: number;
  private readonly ttlMs: number;

  constructor(maxSize = 32, ttlMs = 60_000) {
    this.maxSize = maxSize;
    this.ttlMs = ttlMs;
  }

  static compositeKey(identityHash: number, roomId: string): string {
    return `${identityHash}:${roomId}`;
  }

  set(key: string, meta: SessionMeta): void {
    this.evictExpired();
    if (this.store.size >= this.maxSize && !this.store.has(key)) {
      const oldest = this.store.keys().next().value;
      if (oldest !== undefined) this.store.delete(oldest);
    }
    this.store.set(key, meta);
  }

  get(key: string): SessionMeta | null {
    const meta = this.store.get(key) ?? null;
    if (meta !== null && Date.now() - meta.createdAt > this.ttlMs) {
      this.store.delete(key);
      return null;
    }
    return meta;
  }

  delete(key: string): boolean {
    return this.store.delete(key);
  }

  get size(): number {
    return this.store.size;
  }

  private evictExpired(): void {
    const now = Date.now();
    for (const [key, meta] of this.store) {
      if (now - meta.createdAt > this.ttlMs) {
        this.store.delete(key);
      }
    }
  }
}

let _currentSession: SessionMeta | null = null;

export const threadLocal = {
  set(meta: SessionMeta): void {
    _currentSession = meta;
  },
  get(): SessionMeta | null {
    return _currentSession;
  },
  remove(): void {
    _currentSession = null;
  },
};
```

- [ ] **Step 2: 테스트 실행 — 통과 확인**

Run: `cd frida/agent && npx tsx --test test/session.test.ts`
Expected: 모든 테스트 PASS

- [ ] **Step 3: 커밋**

```bash
git add frida/agent/shared/session.ts frida/agent/test/session.test.ts
git commit -m "feat(agent): SessionStore + ThreadLocal — session chain 기반 모듈

session chain graft의 핵심 데이터 구조:
- SessionStore: TTL(60s) + 크기(32) 제한 Map, composite key
- ThreadLocal: Frida V8 직렬화 기반 module-level 변수
- SessionMeta: sessionId/threadId/threadScope/roomId/createdAt"
```

---

### Task 3: shared/log.ts 생성

**Files:**
- Create: `frida/agent/shared/log.ts`

- [ ] **Step 1: 구조화 로그 helper 작성**

```typescript
type LogSink = (message: string) => void;

let _sink: LogSink = (message) => {
  if (typeof send === 'function') {
    send(message);
  }
};

export function setSink(sink: LogSink): void {
  _sink = sink;
}

export function log(message: string): void {
  _sink(message);
}
```

- [ ] **Step 2: 커밋**

```bash
git add frida/agent/shared/log.ts
git commit -m "feat(agent): shared/log.ts — send() 기반 구조화 로그 helper"
```

---

### Task 4: shared/kakao.ts — HookSpec + IRIS_EXTRAS 추가

**Files:**
- Modify: `frida/agent/shared/kakao.ts`

- [ ] **Step 1: HookSpec 타입, ConnectionOsStream 상수, IRIS_EXTRAS 추가**

기존 `KAKAO_IMAGE_GRAFT_TARGET`과 `KAKAO_MARKDOWN_GRAFT_TARGET`은 유지한다 (markdown graft에서 사용).

파일 전체를 다음으로 교체:

```typescript
// 기존 상수 — markdown graft에서 참조
export const KAKAO_IMAGE_GRAFT_TARGET = {
  className: 'com.kakao.talk.manager.send.ChatSendingLogRequest$a',
  methodName: 'u',
  attachmentField: 'G',
  threadScopeField: 'Z',
  threadIdField: 'V0',
} as const;

export const KAKAO_MARKDOWN_GRAFT_TARGET = {
  className: 'com.kakao.talk.manager.send.ChatSendingLogRequest$a',
  methodName: 'u',
  threadScopeField: 'Z',
  threadIdField: 'V0',
} as const;

// Session chain hook spec
export type HookSpec = {
  versionCode: number;
  connectionOsStream: { className: string; sendMethod: string };
  sendingLogRequest: { className: string; uMethod: string };
  sendingLogFields: { attachment: string; scope: string; threadId: string };
};

export const CURRENT_HOOK_SPEC: HookSpec = {
  versionCode: 0,
  connectionOsStream: {
    className: 'ry0.y',
    sendMethod: 'b',
  },
  sendingLogRequest: {
    className: 'com.kakao.talk.manager.send.ChatSendingLogRequest$a',
    uMethod: 'u',
  },
  sendingLogFields: {
    attachment: 'G',
    scope: 'Z',
    threadId: 'V0',
  },
};

// Intent extra 이름 (Iris Kotlin ReplyService와 동일)
export const IRIS_EXTRAS = {
  SESSION_ID: 'party.qwer.iris.extra.SHARE_SESSION_ID',
  THREAD_ID: 'party.qwer.iris.extra.THREAD_ID',
  THREAD_SCOPE: 'party.qwer.iris.extra.THREAD_SCOPE',
  ROOM_ID: 'party.qwer.iris.extra.ROOM_ID',
  CREATED_AT: 'party.qwer.iris.extra.CREATED_AT',
} as const;
```

- [ ] **Step 2: 커밋**

```bash
git add frida/agent/shared/kakao.ts
git commit -m "feat(agent): HookSpec + IRIS_EXTRAS — session chain hook 대상 상수

ConnectionOsStream(C0694y) 클래스/메서드 + intent extra namespace 정의.
기존 KAKAO_*_GRAFT_TARGET 상수는 markdown graft 호환을 위해 유지."
```

---

### Task 5: shared/message.ts — token 함수 제거

**Files:**
- Modify: `frida/agent/shared/message.ts`
- Modify: `frida/agent/test/message.test.ts`

- [ ] **Step 1: token 관련 함수 제거**

`shared/message.ts`에서 다음을 **삭제**:
- `collectStringCandidates()` 함수 전체
- `extractPathCandidatesFromAttachment()` 함수 전체

다음을 **유지**:
- `isPhotoMessageType()`
- `roomMatchesThreadHint()` (이름은 유지 — 기존 markdown test에서 참조 가능)
- `removeCallingPkgFromAttachment()`

수정 후 파일:

```typescript
export function isPhotoMessageType(messageType: string | null | undefined): boolean {
  return messageType === 'Photo';
}

export function roomMatchesThreadHint(roomId: string | null | undefined, hintRoom: string): boolean {
  return roomId === hintRoom;
}

export function removeCallingPkgFromAttachment(attachmentText: string | null | undefined): string | null {
  if (attachmentText == null) {
    return null;
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(attachmentText);
  } catch {
    return attachmentText;
  }

  if (parsed == null || Array.isArray(parsed) || typeof parsed !== 'object') {
    return attachmentText;
  }

  const payload = { ...(parsed as Record<string, unknown>) };
  if (!Object.prototype.hasOwnProperty.call(payload, 'callingPkg')) {
    return attachmentText;
  }

  delete payload.callingPkg;
  return JSON.stringify(payload);
}
```

- [ ] **Step 2: test/message.test.ts에서 token 관련 테스트 제거**

`extractPathCandidatesFromAttachment` 관련 테스트 케이스를 삭제한다. `callingPkg` 제거 + `isPhotoMessageType` + `roomMatchesThreadHint` 테스트만 유지한다.

기존 test/message.test.ts를 읽고, token/path 관련 import와 테스트만 제거한다.

- [ ] **Step 3: 테스트 실행**

Run: `cd frida/agent && npx tsx --test test/message.test.ts`
Expected: 남은 테스트 PASS

- [ ] **Step 4: 커밋**

```bash
git add frida/agent/shared/message.ts frida/agent/test/message.test.ts
git commit -m "refactor(agent): message.ts에서 token 관련 함수 제거

session chain에서 파일명 token 복구는 불필요.
callingPkg 제거/isPhotoMessageType/roomMatch는 유지."
```

---

### Task 6: thread-image-graft.ts — 3-hook session chain 재작성 + hint.ts 삭제

**Files:**
- Delete: `frida/agent/shared/hint.ts`
- Delete: `frida/agent/test/hint.test.ts`
- Rewrite: `frida/agent/thread-image-graft.ts`
- Rewrite: `frida/agent/test/image-flow.test.ts`

hint.ts 삭제와 thread-image-graft.ts 재작성을 동시에 수행하여 중간에 broken tree가 발생하지 않도록 한다.

- [ ] **Step 1: hint.ts 및 관련 테스트 삭제**

```bash
rm frida/agent/shared/hint.ts frida/agent/test/hint.test.ts
```

- [ ] **Step 2: test/image-flow.test.ts 재작성**

기존 token 기반 테스트를 session chain 데이터 흐름 테스트로 교체한다:

```typescript
import test from 'node:test';
import assert from 'node:assert/strict';
import { SessionStore, threadLocal, type SessionMeta } from '../shared/session.js';
import { removeCallingPkgFromAttachment, isPhotoMessageType } from '../shared/message.js';

const makeMeta = (overrides: Partial<SessionMeta> = {}): SessionMeta => ({
  sessionId: 'uuid-test',
  threadId: '3803466729815130113',
  threadScope: 2,
  roomId: '18476130232878491',
  createdAt: Date.now(),
  ...overrides,
});

test('session chain: store -> threadLocal -> inject cycle', () => {
  const store = new SessionStore();
  const meta = makeMeta();
  const key = SessionStore.compositeKey(12345, meta.roomId);

  // Hook A: ingress
  store.set(key, meta);

  // Hook B: bridge
  const retrieved = store.get(key);
  assert.notEqual(retrieved, null);
  threadLocal.set(retrieved!);

  // Hook C: inject
  const injectionMeta = threadLocal.get();
  assert.deepEqual(injectionMeta, meta);
  assert.equal(injectionMeta!.threadId, '3803466729815130113');
  assert.equal(injectionMeta!.threadScope, 2);

  // Hook B finally: cleanup
  threadLocal.remove();
  store.delete(key);
  assert.equal(threadLocal.get(), null);
  assert.equal(store.get(key), null);
});

test('session chain: non-iris share skipped (no extras)', () => {
  // extras 없으면 sessionStore에 저장하지 않음
  // threadLocal은 null → Hook C에서 skip
  threadLocal.remove();
  assert.equal(threadLocal.get(), null);
});

test('session chain: room mismatch prevents injection', () => {
  const store = new SessionStore();
  const meta = makeMeta({ roomId: 'room-A' });
  const key = SessionStore.compositeKey(999, 'room-A');
  store.set(key, meta);

  const retrieved = store.get(key)!;
  threadLocal.set(retrieved);

  const liveRoom = 'room-B';
  assert.notEqual(retrieved.roomId, liveRoom);
  // -> skip injection, do not modify sendingLog

  threadLocal.remove();
  store.delete(key);
});

test('session chain: parallel sessions stay isolated', () => {
  const store = new SessionStore();
  const meta1 = makeMeta({ sessionId: 's1', roomId: 'R1' });
  const meta2 = makeMeta({ sessionId: 's2', roomId: 'R1' });

  const key1 = SessionStore.compositeKey(100, 'R1');
  const key2 = SessionStore.compositeKey(200, 'R1');

  store.set(key1, meta1);
  store.set(key2, meta2);

  // Frida V8 직렬화: session 1 chain 완료 후 session 2 실행
  threadLocal.set(store.get(key1)!);
  assert.equal(threadLocal.get()!.sessionId, 's1');
  threadLocal.remove();

  threadLocal.set(store.get(key2)!);
  assert.equal(threadLocal.get()!.sessionId, 's2');
  threadLocal.remove();

  store.delete(key1);
  store.delete(key2);
});

test('callingPkg removal works for Photo regardless of session', () => {
  assert.equal(isPhotoMessageType('Photo'), true);
  assert.equal(isPhotoMessageType('Text'), false);

  const att = '{"callingPkg":"com.android.shell","url":"file:///sdcard/img.jpg"}';
  const cleaned = removeCallingPkgFromAttachment(att);
  assert.equal(cleaned, '{"url":"file:///sdcard/img.jpg"}');
});

test('callingPkg removal is no-op when absent', () => {
  const att = '{"url":"file:///sdcard/img.jpg"}';
  assert.equal(removeCallingPkgFromAttachment(att), att);
});
```

- [ ] **Step 3: 테스트 실행 — 통과 확인**

Run: `cd frida/agent && npx tsx --test test/image-flow.test.ts`
Expected: 모든 테스트 PASS (shared 모듈만 사용, hook runtime 불필요)

- [ ] **Step 4: thread-image-graft.ts 재작성**

```typescript
import { SessionStore, threadLocal, type SessionMeta } from './shared/session.js';
import { CURRENT_HOOK_SPEC, IRIS_EXTRAS, type HookSpec } from './shared/kakao.js';
import { isPhotoMessageType, removeCallingPkgFromAttachment } from './shared/message.js';
import { log } from './shared/log.js';

type GraftConfig = {
  discovery: boolean;
  spec: HookSpec;
};

const sessionStore = new SessionStore();

function readStringExtra(intent: any, name: string): string | null {
  try {
    const value = intent.getStringExtra(name);
    return value != null ? String(value) : null;
  } catch {
    return null;
  }
}

function readIntExtra(intent: any, name: string, fallback: number): number {
  try {
    return intent.getIntExtra(name, fallback);
  } catch {
    return fallback;
  }
}

function readLongExtra(intent: any, name: string, fallback: number): number {
  try {
    return intent.getLongExtra(name, fallback);
  } catch {
    return fallback;
  }
}

function rewriteAttachment(sendingLog: any, fieldName: string, cleaned: string): boolean {
  try {
    const cls = sendingLog.getClass();
    const attachField = cls.getDeclaredField(fieldName);
    attachField.setAccessible(true);
    const att = attachField.get(sendingLog);
    if (att == null) return false;

    const attClass = att.getClass();
    const fields = attClass.getDeclaredFields();
    for (const f of fields) {
      f.setAccessible(true);
      if (f.getType().getName() === 'java.lang.String') {
        const val = f.get(att);
        if (val != null && val.toString().includes('callingPkg')) {
          f.set(att, cleaned);
          return true;
        }
      }
    }
  } catch {
    // fail-open: callingPkg 제거 실패해도 전송은 계속
  }
  return false;
}

function readAttachmentText(sendingLog: any, fieldName: string): string | null {
  try {
    const cls = sendingLog.getClass();
    const attachField = cls.getDeclaredField(fieldName);
    attachField.setAccessible(true);
    const att = attachField.get(sendingLog);
    return att != null ? att.toString() : null;
  } catch {
    return null;
  }
}

function tid(): number {
  try {
    return Java.use('android.os.Process').myTid();
  } catch {
    return -1;
  }
}

function installHooks(config: GraftConfig): boolean {
  const spec = config.spec;
  const System = Java.use('java.lang.System');
  const JString = Java.use('java.lang.String');
  const JLong = Java.use('java.lang.Long');

  // Hook A: C0694y 생성자 (Ingress)
  // intent에서 session extras를 읽어 sessionStore에 저장
  try {
    const ConnectionOsStream = Java.use(spec.connectionOsStream.className);
    const ctorOverloads = ConnectionOsStream.$init.overloads;
    let hooked = false;

    for (const ctor of ctorOverloads) {
      const paramTypes = ctor.argumentTypes.map((t: any) => t.className);
      if (!paramTypes.includes('android.content.Intent')) continue;

      const intentIdx = paramTypes.indexOf('android.content.Intent');
      ctor.implementation = function (...args: any[]) {
        this.$init(...args);

        const intent = args[intentIdx];
        const sessionId = readStringExtra(intent, IRIS_EXTRAS.SESSION_ID);
        if (sessionId == null) return;

        const threadId = readStringExtra(intent, IRIS_EXTRAS.THREAD_ID);
        const threadScope = readIntExtra(intent, IRIS_EXTRAS.THREAD_SCOPE, 0);
        const roomId = readStringExtra(intent, IRIS_EXTRAS.ROOM_ID);
        const createdAt = readLongExtra(intent, IRIS_EXTRAS.CREATED_AT, Date.now());

        if (roomId == null || threadId == null) {
          log(`[A] incomplete extras session=${sessionId}`);
          return;
        }

        const hash = System.identityHashCode(this);
        const key = SessionStore.compositeKey(hash, roomId);
        const meta: SessionMeta = { sessionId, threadId, threadScope, roomId, createdAt };
        sessionStore.set(key, meta);

        log(`[A] ingress session=${sessionId} room=${roomId} threadId=${threadId} scope=${threadScope} tid=${tid()}`);
      };
      hooked = true;
    }

    if (!hooked) {
      log('[A] no Intent constructor found');
      return false;
    }
  } catch (e) {
    log(`[A] hook install failed: ${e}`);
    return false;
  }

  // Hook B: C0694y.b() (Bridge)
  // sessionStore에서 meta를 꺼내 threadLocal에 설정, 원본 호출 후 정리
  try {
    const ConnectionOsStream = Java.use(spec.connectionOsStream.className);
    const bOverloads = ConnectionOsStream[spec.connectionOsStream.sendMethod].overloads;

    for (const overload of bOverloads) {
      overload.implementation = function (...args: any[]) {
        // args[1]이 chatRoomId (long)
        let roomIdStr: string | null = null;
        try {
          roomIdStr = JString.valueOf(args[1]);
        } catch {
          return overload.apply(this, args);
        }

        const hash = System.identityHashCode(this);
        const key = SessionStore.compositeKey(hash, roomIdStr);
        const meta = sessionStore.get(key);

        if (meta == null) {
          log(`[B] no session in store key=${key} tid=${tid()}`);
        } else {
          threadLocal.set(meta);
          log(`[B] bridge session=${meta.sessionId} roomId=${roomIdStr} connectionHash=${hash} tid=${tid()}`);
        }

        try {
          return overload.apply(this, args);
        } finally {
          threadLocal.remove();
          if (meta != null) {
            sessionStore.delete(key);
          }
        }
      };
    }
  } catch (e) {
    log(`[B] hook install failed: ${e}`);
    return false;
  }

  // Hook C: u() (Inject)
  // threadLocal에서 meta를 읽어 sendingLog에 thread 정보 주입
  // callingPkg 제거는 모든 Photo에서 실행 (session 유무 무관)
  try {
    const RequestCompanion = Java.use(spec.sendingLogRequest.className);
    const uOverloads = RequestCompanion[spec.sendingLogRequest.uMethod].overloads;

    for (const overload of uOverloads) {
      overload.implementation = function (...args: any[]) {
        const sendingLog = args[1];

        // message type 확인
        let messageType: string | null = null;
        try { messageType = sendingLog.w0().toString(); } catch {}

        // Photo에서 callingPkg 제거 (session 유무 무관 — legacy parity)
        if (isPhotoMessageType(messageType)) {
          const attText = readAttachmentText(sendingLog, spec.sendingLogFields.attachment);
          const cleaned = removeCallingPkgFromAttachment(attText);
          if (cleaned != null && cleaned !== attText) {
            if (rewriteAttachment(sendingLog, spec.sendingLogFields.attachment, cleaned)) {
              log('[C] callingPkg removed');
            }
          }
        }

        // session inject
        const meta = threadLocal.get();
        if (meta == null) {
          log(`[C] u() no session in threadLocal tid=${tid()}`);
          return overload.apply(this, args);
        }

        // roomId 검증
        let liveRoomId: string | null = null;
        try {
          liveRoomId = JString.valueOf(sendingLog.getChatRoomId());
        } catch {}

        const roomMatch = liveRoomId === meta.roomId;
        if (!roomMatch) {
          log(`[C] u() roomMatch=false expected=${meta.roomId} got=${liveRoomId} session=${meta.sessionId} tid=${tid()}`);
          return overload.apply(this, args);
        }

        // discovery mode: 로그만 남기고 주입하지 않음
        if (config.discovery) {
          log(`[C] u() session=${meta.sessionId} roomMatch=true tid=${tid()} -> WOULD_INJECT threadId=${meta.threadId} scope=${meta.threadScope}`);
          return overload.apply(this, args);
        }

        // 실제 주입
        try {
          const cls = sendingLog.getClass();

          const scopeField = cls.getDeclaredField(spec.sendingLogFields.scope);
          scopeField.setAccessible(true);
          scopeField.setInt(sendingLog, meta.threadScope);

          const threadIdField = cls.getDeclaredField(spec.sendingLogFields.threadId);
          threadIdField.setAccessible(true);
          threadIdField.set(sendingLog, JLong.valueOf(JString.valueOf(meta.threadId)));

          log(`[C] injected session=${meta.sessionId} threadId=${meta.threadId} scope=${meta.threadScope}`);
        } catch (e) {
          log(`[C] injection failed session=${meta.sessionId}: ${e}`);
        }

        return overload.apply(this, args);
      };
    }
  } catch (e) {
    log(`[C] hook install failed: ${e}`);
    return false;
  }

  return true;
}

// 진입점
if (Java.available) {
  Java.perform(() => {
    const graftConfig: GraftConfig = {
      discovery: (globalThis as any).__IRIS_DISCOVERY === true,
      spec: CURRENT_HOOK_SPEC,
    };

    const ok = installHooks(graftConfig);
    send(JSON.stringify({ type: 'readiness', ready: ok }));
    log(`[graft] session-chain hooks ${ok ? 'installed' : 'FAILED'}`);
  });
}
```

- [ ] **Step 5: typecheck 확인**

Run: `cd frida/agent && npx tsc --noEmit`
Expected: 오류 없음 (hint.ts 삭제 + thread-image-graft.ts 재작성으로 정합)

- [ ] **Step 6: 전체 테스트 실행**

Run: `cd frida/agent && npx tsx --test test/session.test.ts test/image-flow.test.ts test/message.test.ts test/markdown-flow.test.ts`
Expected: 모든 테스트 PASS

- [ ] **Step 7: 커밋**

```bash
git add -u frida/agent/shared/hint.ts frida/agent/test/hint.test.ts
git add frida/agent/thread-image-graft.ts frida/agent/test/image-flow.test.ts
git commit -m "feat(agent): 3-hook session chain image graft + hint.ts 삭제

token 기반 hint file claim -> session chain 전환:
- Hook A (C0694y 생성자): intent extras -> sessionStore
- Hook B (C0694y.b()): sessionStore -> threadLocal, finally 정리
- Hook C (u()): threadLocal -> sendingLog field inject
- callingPkg 제거: 모든 Photo에서 session 유무 무관 실행
- fail-closed: sessionStore miss/threadLocal miss/roomId mismatch -> skip + 로그
- discovery mode: --discovery 시 로그만 남기고 주입 안 함
- readiness 메시지: {type:'readiness', ready:bool}
- shared/hint.ts 삭제 (session chain이 대체)"
```

---

### Task 7: Kotlin ReplyService.kt — session extras + writeThreadHint 게이트

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/ReplyService.kt`

- [ ] **Step 1: intent extra 상수 추가**

`private companion object` 블록에 추가:

```kotlin
private const val EXTRA_IRIS_SESSION_ID = "party.qwer.iris.extra.SHARE_SESSION_ID"
private const val EXTRA_IRIS_THREAD_ID = "party.qwer.iris.extra.THREAD_ID"
private const val EXTRA_IRIS_THREAD_SCOPE = "party.qwer.iris.extra.THREAD_SCOPE"
private const val EXTRA_IRIS_ROOM_ID = "party.qwer.iris.extra.ROOM_ID"
private const val EXTRA_IRIS_CREATED_AT = "party.qwer.iris.extra.CREATED_AT"
```

- [ ] **Step 2: hintFileDebugEnabled 프로퍼티 추가**

클래스 본문에 추가 (imageMediaScanEnabled 근처):

```kotlin
private val hintFileDebugEnabled = System.getenv("IRIS_HINT_FILE_DEBUG") == "1"
```

- [ ] **Step 3: PreparedImages에 session 필드 추가**

파일 하단의 `PreparedImages` data class를 수정:

```kotlin
private data class PreparedImages(
    val room: Long,
    val uris: ArrayList<Uri>,
    val files: ArrayList<File>,
    val sessionId: String? = null,
    val threadId: Long? = null,
    val threadScope: Int? = null,
)
```

- [ ] **Step 4: sendMultiplePhotos — sessionId 생성 + writeThreadHint 게이트**

`sendMultiplePhotos` 메서드의 `return enqueueRequest` 블록을 수정:

```kotlin
override fun sendMultiplePhotos(
    room: Long,
    base64ImageDataStrings: List<String>,
    threadId: Long?,
    threadScope: Int?,
): ReplyAdmissionResult {
    val decodedImages =
        try {
            require(base64ImageDataStrings.isNotEmpty()) { "no image data provided" }
            base64ImageDataStrings.map { base64 ->
                require(base64.length <= MAX_BASE64_IMAGE_PAYLOAD_LENGTH) { "payload exceeds size limit" }
                decodeBase64Image(base64)
            }
        } catch (_: IllegalArgumentException) {
            return ReplyAdmissionResult(
                ReplyAdmissionStatus.INVALID_PAYLOAD,
                "image replies require valid base64 payload",
            )
        }

    val sessionId =
        if (threadId != null && threadScope != null && threadScope >= 2) {
            java.util.UUID.randomUUID().toString()
        } else {
            null
        }

    return enqueueRequest(
        SendMessageRequest {
            if (hintFileDebugEnabled) writeThreadHint(room, threadId, threadScope)
            sendDecodedImages(room, decodedImages, sessionId, threadId, threadScope)
        },
    )
}
```

- [ ] **Step 5: sendDecodedImages — session 파라미터 전달**

```kotlin
private fun sendDecodedImages(
    room: Long,
    decodedImages: List<ByteArray>,
    sessionId: String? = null,
    threadId: Long? = null,
    threadScope: Int? = null,
) {
    ensureImageDir(imageDir)
    val uris = ArrayList<Uri>(decodedImages.size)
    val createdFiles = ArrayList<File>(decodedImages.size)
    try {
        decodedImages.forEach { imageBytes ->
            val imageFile = saveImage(imageBytes, imageDir)
            createdFiles.add(imageFile)
            val imageUri = Uri.fromFile(imageFile)
            if (imageMediaScanEnabled) {
                mediaScan(imageUri)
            }
            uris.add(imageUri)
        }
        require(uris.isNotEmpty()) { "no image URIs created" }
        sendPreparedImages(
            PreparedImages(
                room = room,
                uris = uris,
                files = createdFiles,
                sessionId = sessionId,
                threadId = threadId,
                threadScope = threadScope,
            ),
        )
    } catch (e: Exception) {
        createdFiles.forEach { file ->
            if (file.exists() && !file.delete()) {
                IrisLogger.error("Failed to delete partially prepared image file: ${file.absolutePath}")
            }
        }
        throw e
    }
}
```

- [ ] **Step 6: sendPreparedImages — intent에 session extras 삽입**

```kotlin
private fun sendPreparedImages(preparedImages: PreparedImages) {
    val intent =
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            setPackage("com.kakao.talk")
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, preparedImages.uris)
            putExtra("key_id", preparedImages.room)
            putExtra("key_type", 1)
            putExtra("key_from_direct_share", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

            preparedImages.sessionId?.let { sid ->
                identifier = "iris:$sid"
                putExtra(EXTRA_IRIS_SESSION_ID, sid)
                putExtra(EXTRA_IRIS_THREAD_ID, preparedImages.threadId?.toString().orEmpty())
                putExtra(EXTRA_IRIS_THREAD_SCOPE, preparedImages.threadScope ?: 0)
                putExtra(EXTRA_IRIS_ROOM_ID, preparedImages.room.toString())
                putExtra(EXTRA_IRIS_CREATED_AT, System.currentTimeMillis())
            }
        }

    try {
        AndroidHiddenApi.startActivity(intent)
    } catch (e: Exception) {
        IrisLogger.error("Error starting activity for sending multiple photos: $e")
        cleanupPreparedImages(preparedImages)
        throw e
    }
}
```

- [ ] **Step 7: 빌드 + lint 확인**

Run: `./gradlew ktlintFormat && ./gradlew lint ktlintCheck assembleDebug`
Expected: 빌드 성공, lint 경고 없음

- [ ] **Step 8: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/ReplyService.kt
git commit -m "feat(iris): image send intent에 session chain extras 삽입

- sessionId/threadId/threadScope/roomId/createdAt extras 추가
- threadId != null && threadScope >= 2 조건에서만 session 생성
- intent.identifier에 sessionId 이중화 (extras 소실 방어)
- writeThreadHint()를 IRIS_HINT_FILE_DEBUG=1 환경변수 게이트로 전환"
```

---

### Task 8: Go daemon — --discovery + --hook-spec 플래그

**Files:**
- Modify: `frida/daemon/internal/app/config.go`
- Modify: `frida/daemon/internal/app/config_test.go`

- [ ] **Step 1: Config에 Discovery + HookSpec 필드 추가**

`config.go`의 `Config` struct에 필드 추가:

```go
type Config struct {
	AgentProjectRoot       string
	AgentEntryPoint        string
	DeviceID               string
	FridaCoreDevkitDir     string
	PidPollIntervalSeconds int
	RetryDelaySeconds      int
	Discovery              bool
	HookSpec               string
}
```

`ParseConfig`의 `flag` 등록 + 대입:

```go
discovery := fs.Bool("discovery", false, "discovery mode: log only, no injection")
hookSpec := fs.String("hook-spec", "default", "hook spec version (default: auto-detect)")
```

반환 struct에 추가:

```go
return Config{
	// ... existing fields ...
	Discovery: *discovery,
	HookSpec:  *hookSpec,
}, nil
```

- [ ] **Step 2: config_test.go에 새 플래그 테스트 추가**

기존 테스트 파일을 읽고, `--discovery` 플래그 파싱 검증 케이스를 추가:

```go
func TestParseConfigDiscovery(t *testing.T) {
	cfg, err := ParseConfig("/repo", []string{
		"--agent", "thread-image-graft.ts",
		"--discovery",
	})
	if err != nil {
		t.Fatal(err)
	}
	if !cfg.Discovery {
		t.Error("expected Discovery=true")
	}
	if cfg.HookSpec != "default" {
		t.Errorf("expected HookSpec=default, got %s", cfg.HookSpec)
	}
}
```

- [ ] **Step 3: 테스트 실행**

Run: `cd frida/daemon && go test ./internal/app/...`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add frida/daemon/internal/app/config.go frida/daemon/internal/app/config_test.go
git commit -m "feat(daemon): --discovery, --hook-spec 플래그 추가

discovery mode에서 agent가 inject 없이 로그만 기록.
hook-spec으로 버전별 hook 대상 명시적 지정 가능."
```

---

## Discovery mode 실기기 검증 체크리스트 (plan 범위 밖, 참고용)

구현 완료 후 Redroid 환경에서 discovery mode로 다음을 검증한다:

1. single room / single thread / single image → A→B→C 체인 tid 동일 확인
2. single room / single thread / multiple images → u() 호출 횟수 확인
3. same room / different threads / parallel send → session 격리 확인
4. session extras 누락 시 Hook A skip 확인
5. roomId mismatch 시 Hook C skip 확인 (fail-closed)

discovery mode 검증 통과 후 `__IRIS_DISCOVERY = false`로 production inject 전환.
