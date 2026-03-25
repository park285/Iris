# Session Chain Graft 설계

> 작성일: 2026-03-25
> 범위: image thread graft를 session chain 기반으로 재설계 + frida/ 계층 TS/Go 전환 통합
> 선행 문서: `2026-03-24-frida-ts-go-graft-design.md` (TS/Go 전환), `doc.md` (session chain 원안)

---

## 배경

### 이전 시도와 실패

1. **전역 hint 파일** (`/data/local/tmp/iris-thread-hint.json`)
   - `writeThreadHint()` → `sendDecodedImages()` 순서로 hint 기록 후 이미지 전송
   - correctness가 Iris의 직렬 처리에 의존
   - `startActivity()`가 fire-and-forget이므로, KakaoTalk 내부 `u()` 호출 시점에 다음 hint가 이미 덮어쓰여질 수 있음
   - same-room different-thread 동시 전송에서 구조적으로 race condition 발생

2. **token 기반 파일** (`iris-graft-<token>-<index>.<ext>` + `pending/<token>.json`)
   - 요청별 UUID token을 이미지 파일명과 hint 파일에 삽입
   - 실기기 검증 결과, hook point `ChatSendingLogRequest$a.u()` 시점에서 sendingLog 객체에 tokenized 파일명/경로가 보이지 않음
   - `G` 필드와 `t()` 모두 `{"callingPkg":"com.kakao.talk"}`만 반환
   - token 복구 불가로 접근 자체가 불가능

3. **per-room hint / consumption fence**
   - per-room hint: same-room race는 전역 hint와 동일하게 발생
   - consumption fence: thread별 비동기 발송 요구사항과 충돌 (직렬화 강제)

### RE 검증 결과 (2026-03-25)

KakaoTalk APK(jadx 디컴파일)에 대한 정적 분석으로 다음을 확인:

- Iris의 `ACTION_SEND_MULTIPLE` intent는 `BaseIntentFilterActivity` → `TaskRootActivity` → `ChatRoomHolderActivity` → `C0694y`(ConnectionOsStream) 생성자까지 extras가 보존됨
- `C0694y.b()` → `C0659N`(MediaBatchBroadcastWorker) → `h()` → `m()` → `u()`가 **동일 스레드에서 동기 호출 체인**으로 실행됨
- doc.md가 제안한 B1(Normalizer) 단계는 별도로 존재하지 않음 — URI가 변환 없이 전달됨
- 따라서 4-hook이 아닌 **3-hook + thread-local** 구조로 단순화 가능

---

## 목표

- same-room different-thread 동시 이미지 전송에서 오배정 0건
- room/FIFO/consumption fence 없이 비동기 병렬 전송 가능
- attachment/파일명 token 복구에 의존하지 않음
- request object 단위로 thread 주입
- hook point가 깨지면 fail-closed (조용한 오작동 방지)
- `frida/` 계층을 TypeScript agent + Go daemon 구조로 전환

## 비목표

- 앱의 `/reply` public contract 변경
- KakaoTalk hook 대상 클래스/필드의 의미 변경
- observer/webhook/reply pipeline 리팩토링
- text thread reply 경로 변경 (기존 NotificationActionService 경로 유지)
- markdown graft 경로 변경 (기존 marker 기반 유지)

---

## Iris 측 변경

### writeThreadHint() 제거 → custom intent extras 전환

기존:
```kotlin
writeThreadHint(room, threadId, threadScope)
sendDecodedImages(room, decodedImages)
```

변경:
```kotlin
val sessionId = UUID.randomUUID().toString()
// sendMultiplePhotos → sendDecodedImages → sendPreparedImages 체인에 session 전달
// PreparedImages data class에 sessionId/threadId/threadScope 필드 추가
sendDecodedImages(room, decodedImages, sessionId, threadId, threadScope)
```

변경이 필요한 Kotlin 범위:
- `sendMultiplePhotos()`: sessionId 생성, `sendDecodedImages`에 전달
- `sendDecodedImages()`: session 파라미터 수신, `PreparedImages`에 포함
- `PreparedImages` data class: `sessionId`, `threadId`, `threadScope` 필드 추가
- `sendPreparedImages()`: intent 생성 시 session extras 삽입

### Intent extras

```kotlin
const val EXTRA_IRIS_SESSION_ID = "party.qwer.iris.extra.SHARE_SESSION_ID"
const val EXTRA_IRIS_THREAD_ID = "party.qwer.iris.extra.THREAD_ID"
const val EXTRA_IRIS_THREAD_SCOPE = "party.qwer.iris.extra.THREAD_SCOPE"
const val EXTRA_IRIS_ROOM_ID = "party.qwer.iris.extra.ROOM_ID"
const val EXTRA_IRIS_CREATED_AT = "party.qwer.iris.extra.CREATED_AT"
```

image send intent에 추가:
```kotlin
Intent(Intent.ACTION_SEND_MULTIPLE).apply {
    // ... 기존 extras 유지 ...
    identifier = "iris:$sessionId"  // extras 소실 시 백업 식별자
    putExtra(EXTRA_IRIS_SESSION_ID, sessionId)
    putExtra(EXTRA_IRIS_THREAD_ID, threadId)
    putExtra(EXTRA_IRIS_THREAD_SCOPE, threadScope)
    putExtra(EXTRA_IRIS_ROOM_ID, room.toString())
    putExtra(EXTRA_IRIS_CREATED_AT, System.currentTimeMillis())
}
```

설계 결정:
- extra 이름은 KakaoTalk의 `thread_id`, `scope`와 반드시 다른 namespace를 사용한다 (충돌 방지)
- `intent.identifier`에 sessionId를 이중 기록한다 — 일부 내부 clone/sanitize에서 extras가 소실되어도 identifier가 남을 수 있음
- `CREATED_AT`는 agent 측 SessionStore TTL 관리에 사용
- doc.md 원안의 `MEDIA_COUNT`는 제외 — agent가 URI 개수를 직접 세면 되므로 진단 가치 대비 extras 수를 줄임

### writeThreadHint() 처리

- 주 경로에서 제거
- 디버그 fallback으로 환경변수 게이트(`IRIS_HINT_FILE_DEBUG=1`) 뒤에 잔류 가능
- 기본값은 비활성

### thread 정보가 없는 image send

image thread graft가 필요한 조건: `threadId != null && threadScope >= 2`.
이 조건을 만족하지 않으면 session extras를 넣지 않는다.

참고: text reply 경로에서는 `threadScope=1 + threadId=null`이 허용되지만(`is_chat_thread_notification` extra로 처리), image graft 경로는 Frida hook 기반이므로 `threadScope >= 2`만 대상이다.

agent는 extras 부재 시 plain image send로 통과시킨다.

---

## Frida Agent 설계

### Hook 구조 (3-hook + thread-local)

RE 검증으로 확인된 동기 호출 체인:
```
C0694y(intent)              [ConnectionOsStream 생성자]
  ↓
C0694y.b(listener, roomId)  [chatroom send - 동기]
  ↓
C0659N(roomId, uris, ...)   [MediaBatchBroadcastWorker - 동기]
  ↓ h() → l() → m()
ChatSendingLogRequest.Companion.u(room, sendingLog, ...)
```

전체가 동일 스레드에서 동기 실행되므로 thread-local로 session을 전달한다.

#### Hook A: `C0694y` 생성자 (Ingress)

```
대상: com.kakao.talk 내부 C0694y (ConnectionOsStream)
시점: intent에서 ConnectionOsStream 생성 시
```

동작:
1. 생성자 파라미터 `intent`에서 `EXTRA_IRIS_SESSION_ID` 읽기
2. 없으면 skip (non-Iris share)
3. 있으면 `sessionStore.set(identityHashCode(this), SessionMeta{sessionId, threadId, scope, roomId})`

#### Hook B: `C0694y.b(listener, chatRoomId)` (Bridge)

```
대상: C0694y.b() — chatroom 전송 메서드
시점: batch worker 생성 직전
```

동작:
1. `sessionStore.get(identityHashCode(this))` → SessionMeta
2. 없으면 원본 호출만 수행
3. 있으면 `threadLocal.set(sessionMeta)` → 원본 `b()` 호출 → `threadLocal.remove()`

`b()` 내부에서 `C0659N` → `h()` → `m()` → `u()`가 동기로 실행되므로, thread-local이 u()까지 유효하다.

#### Hook C: `ChatSendingLogRequest.Companion.u()` (Inject)

```
대상: com.kakao.talk.manager.send.ChatSendingLogRequest$a.u()
시점: 실제 전송 직전
```

동작:
1. `threadLocal.get()` → SessionMeta
2. 없으면: skip (non-session send 또는 non-Iris)
3. 있으면:
   - roomId 검증 (sendingLog.getChatRoomId() vs sessionMeta.roomId)
   - mismatch면 skip + 로그 (fail-closed)
   - match면:
     - `sendingLog.Z = sessionMeta.scope` (reflection)
     - `sendingLog.V0 = Long.valueOf(sessionMeta.threadId)` (reflection)
4. callingPkg 제거 (모든 Photo에서 실행, session 유무 무관 — legacy parity)
5. 원본 `u()` 호출

### callingPkg 제거 정책

legacy 동작과 동일하게, **모든 Photo에서 session 유무와 무관하게 callingPkg를 제거한다.**

이전 token 기반 agent에서 발생한 JNI crash는 token 없이 attachment JSON 구조 자체를 변형(필드 추가/제거 외의 조작)한 것이 원인이었다. callingPkg 제거는 JSON 내 단일 키 삭제로, legacy에서 안전하게 동작이 확인된 작업이다.

정책:
- Photo: callingPkg 제거 실행 (session 유무 무관, legacy parity)
- non-Photo: 건드리지 않음

### Thread-local 수명

```
C0694y.b() 진입 → threadLocal.set(meta)
  ↓ (동기 체인)
  u() → threadLocal.get() → inject
C0694y.b() 반환 → threadLocal.remove()
```

- `b()`의 finally 블록에서 반드시 `threadLocal.remove()` 실행
- u()가 여러 번 호출될 수 있음 (MultiPhoto feature flag에 따라 1회 또는 N회)
- 동일 session에 대한 중복 inject는 허용 (같은 thread 정보이므로 무해)

Frida Java hook에서의 구현 패턴 (원본 메서드 예외 시에도 정리 보장):
```typescript
cls.b.implementation = function (listener: any, roomId: any) {
  const meta = sessionStore.get(compositeKey(this, roomId));
  if (meta) threadLocal.set(meta);
  try {
    return this.b(listener, roomId);
  } finally {
    threadLocal.remove();
  }
};
```

### Session store 정리

- `sessionStore`는 `Map<string, SessionMeta>` (복합 키 → meta)
- 복합 키: `${identityHashCode}:${roomId}` — identityHashCode 단독으로는 32-bit 공간에서 충돌 가능하므로 roomId를 결합
- set 시점에 동일 키가 이미 존재하면 덮어쓰기 전 경고 로그 남김 (충돌 진단)
- TTL 기반 정리: `createdAt`이 60초 이상 경과한 항목은 다음 접근 시 제거 (createdAt는 intent extra `EXTRA_IRIS_CREATED_AT` 또는 hook 시점 timestamp)
- 최대 크기: 32 (초과 시 가장 오래된 항목 제거)

### Fail policy

| 상황 | 동작 |
|------|------|
| intent에 session extras 없음 | skip (plain image send) |
| sessionStore에서 C0694y 못 찾음 | skip + 로그 |
| threadLocal에 session 없음 (u() 시점) | skip + 로그 |
| roomId mismatch | skip + 로그 (fail-closed) |
| reflection 실패 (Z/V0 필드) | skip + 로그 (fail-closed) |
| hook 대상 class/method 미발견 | 전체 hook 미설치 + readiness=false |

원칙: 모호하면 주입하지 않는다. 잘못된 thread에 보내는 것이 최악이다.

### Discovery mode

production inject 전에 로그만 남기는 모드:

```
[A] ingress session=s1 room=R threadId=T scope=S tid=<OS thread ID>
[B] bridge session=s1 roomId=R connectionHash=H tid=<OS thread ID>
[C] u() session=s1 roomMatch=true tid=<OS thread ID> → WOULD_INJECT threadId=T scope=S
```

`tid` 필드로 동기 체인 가정(A→B→C가 동일 OS 스레드)을 직접 검증한다.

`--discovery` 플래그로 활성화. 이 모드에서 확인할 것:
- A → B → C 체인이 동일 session으로 이어지는가
- same-room parallel send에서 session이 섞이지 않는가
- MultiPhoto에서 u()가 몇 회 호출되는가

---

## KakaoTalk Hook Point Spec

### 확정된 hook 대상 (RE 검증 완료)

| Hook | 클래스 (난독화) | 메서드 | 원본 의미 |
|------|----------------|--------|----------|
| A | `C0694y` (ry0 패키지) | 생성자 `<init>(Intent)` | ConnectionOsStream 생성 |
| B | `C0694y` | `b(m, long)` | chatroom send 실행 |
| C | `ChatSendingLogRequest$a` | `u(hp.t, ChatSendingLog, c, m, boolean)` | 전송 요청 |

### 버전 의존 필드

| 필드 | 현재 이름 | 원본 의미 | 위치 |
|------|----------|----------|------|
| attachment | `G` | ChatSendingLog.attachment | ChatSendingLog |
| scope | `Z` | ChatSendingLog.scope | ChatSendingLog |
| threadId | `V0` | ChatSendingLog.threadId | ChatSendingLog |

### 버전 게이트

난독화 이름은 KakaoTalk 업데이트마다 바뀔 수 있다. Hook spec을 버전별로 관리한다:

```typescript
type HookSpec = {
  versionCode: number;
  connectionOsStream: { className: string; sendMethod: string };
  sendingLogRequest: { className: string; uMethod: string };
  sendingLogFields: { attachment: string; scope: string; threadId: string };
};
```

agent는 부팅 시:
1. target class/method 존재 여부 확인
2. 하나라도 미발견이면 readiness=false → Iris에 threaded image send 불가 통보
3. 모두 발견이면 hook 설치 → readiness=true

Readiness 통보 경로 (별도 설계 필요):
- agent → Go daemon: Frida `send()` 메시지로 `{"type":"readiness","ready":true/false,"version":...}` 전달
- Go daemon → Iris: 구현 계획 단계에서 정의 (후보: health check endpoint 확장, 파일 기반 flag, 또는 gRPC/HTTP callback)
- Iris 측: readiness=false이면 image thread graft API가 503 또는 text fallback 반환

---

## TS/Go 전환 (기존 spec 통합)

### 디렉터리 구조

```
frida/
  legacy/
    thread-image-graft.js
    thread-markdown-graft.js
    graft-daemon.py

  agent/
    package.json
    tsconfig.json
    thread-image-graft.ts      ← session chain hook (A/B/C)
    thread-markdown-graft.ts   ← marker 기반 (기존 parity)
    shared/
      session.ts               ← SessionStore, ThreadLocal, SessionMeta
      kakao.ts                 ← HookSpec, 버전별 상수
      log.ts                   ← send() 기반 구조화 로그
      message.ts               ← callingPkg 제거, room match
    test/
      session.test.ts
      image-flow.test.ts
      markdown-flow.test.ts

기존 `shared/hint.ts`는 제거. session chain에서 hint file은 주 경로가 아니며, `shared/session.ts`가 그 역할을 대체한다. 디버그 fallback(`IRIS_HINT_FILE_DEBUG=1`)이 필요하면 `shared/session.ts` 내부에 선택적 경로로 포함한다.

  daemon/
    go.mod
    cmd/graft-daemon/
      main.go
    internal/
      app/       config, run
      adb/       PID lookup
      agentbuild/ TS compile
      fridaapi/   device/session/script lifecycle
      lifecycle/  PID watch, re-attach, shutdown
      logx/       structured logging
```

### Agent source of truth

- 정본: TypeScript (`frida/agent/`)
- JS는 legacy 백업 또는 generated bundle로만 존재
- TS compile은 Go daemon 내부에서 Frida compiler API로 수행

### Go daemon 책임

기존 `2026-03-24-frida-ts-go-graft-design.md`의 5개 모듈 구조를 유지:
- `adb`: device 선택, PID lookup
- `agentbuild`: TS compile, bundle 생성
- `fridaapi`: attach/session/script lifecycle
- `lifecycle`: PID 변화 감시, 재attach, graceful shutdown
- `app`: config/flag, 모듈 wiring

추가:
- `--discovery` 플래그: agent를 discovery mode로 실행
- `--hook-spec <version>`: 명시적 hook spec 버전 지정 (자동 탐지 실패 시)

### Markdown graft

- 기존 marker 기반 주입 경로 유지
- session chain과 무관 (text 경로는 NotificationActionService 기반)
- TS 전환 + legacy parity가 목표

---

## 백업과 롤백

기존 `2026-03-24` spec과 동일:

- legacy 자산: `frida/legacy/` 보존
- modern: `graft-daemon --agent frida/agent/thread-image-graft.ts`
- legacy: `python3 frida/legacy/graft-daemon.py --script frida/legacy/thread-image-graft.js`
- 활성 profile은 한 시점에 하나
- rollback은 entrypoint 전환으로 정의

---

## 검증 전략

### 정적 검증

- Go build 성공
- TS compile 성공
- hook spec 상수가 현재 KakaoTalk 버전과 일치
- session store TTL/크기 제한 동작

### 단위 검증

- SessionStore: set/get/TTL 만료/크기 초과
- ThreadLocal: set/get/remove/concurrent isolation
- SessionMeta: intent extras parse/validation
- callingPkg 제거: session 유/무 분기
- roomId match/mismatch
- HookSpec: 버전별 조회/fallback
- markdown marker 감지 parity

### Discovery mode 검증 (실기기)

- single room / single thread / single image → A→B→C 체인 확인
- single room / single thread / multiple images → u() 호출 횟수 확인
- same room / different threads / parallel send → session 격리 확인
- session extras 누락 시 skip 확인
- roomId mismatch 시 fail-closed 확인

### Production mode 검증 (실기기)

- 위 시나리오에서 실제 thread inject 확인
- callingPkg 제거 확인
- graceful shutdown 시 hook cleanup 확인
- KakaoTalk 크래시 없음 확인
- legacy rollback entrypoint 실행 가능 확인

---

## 리스크와 대응

### 1. 동기 체인 가정 위반

리스크: KakaoTalk 업데이트에서 `C0694y.b()` → `u()` 경로가 비동기로 변경될 수 있음.

대응:
- discovery mode에서 thread ID 로그로 동기성 검증
- 비동기 전환 감지 시 object identity 기반 fallback 경로 추가 (identityHashCode chain)
- 비동기 전환은 KakaoTalk의 대규모 아키텍처 변경을 의미하므로 발생 가능성은 낮음

### 2. intent extras 소실

리스크: KakaoTalk 내부에서 intent를 재생성하거나 extras를 strip할 수 있음.

대응:
- discovery mode에서 Hook A에서 extras 수신 여부를 최우선 확인
- extras 소실 시 대안: tokenized filename fallback (현재 불가하지만 KakaoTalk이 path를 노출하는 버전이 있을 수 있음)

### 3. 난독화 이름 변경

리스크: `C0694y`, `G`, `Z`, `V0` 등 모든 난독화 이름이 버전마다 바뀜.

대응:
- 버전별 HookSpec 구조
- agent 부팅 시 class/method/field 존재 확인
- 미발견 시 readiness=false → Iris가 threaded image 기능 비활성화
- 역할 기반 탐색 패턴 문서화 (u() xref → constructor → field 순서)

### 4. MultiPhoto feature flag 차이

리스크: `f.a.w2()` 반환값에 따라 u()가 1회 또는 N회 호출됨.

대응:
- thread-local은 b() 전체 구간에서 유효하므로 호출 횟수와 무관하게 동작
- discovery mode에서 실제 호출 횟수 로그로 확인

### 5. clone/copy 문제

리스크: `ChatSendingLog`가 내부적으로 복사되어 다른 identity로 u()에 전달될 수 있음.

대응:
- thread-local 방식이므로 object identity에 의존하지 않음 — 이 문제 자체가 발생하지 않음
- 동기 체인 가정이 유지되는 한 안전

---

## 구현 원칙

- 최소 변경 범위: `frida/` + `ReplyService.kt`의 sendPreparedImages()
- intent extra namespace는 `party.qwer.iris.extra.*`로 고정
- 정본은 TS/Go만 유지
- runtime 불확실성은 discovery mode + fail-closed로 관리
- big-bang 교체하되 legacy rollback은 즉시 가능

---

## 완료 기준

1. Iris가 image send intent에 session extras를 포함한다
2. writeThreadHint()가 주 경로에서 제거된다
3. TS agent가 3-hook 구조로 session chain inject를 수행한다
4. discovery mode에서 A→B→C 체인이 same-room parallel send에서 격리됨을 확인한다
5. production mode에서 same-room different-thread 이미지 전송이 정확하게 동작한다
6. markdown graft가 기존과 동등하게 동작한다
7. Go daemon이 Frida Go API로 attach/lifecycle 관리를 수행한다
8. legacy rollback entrypoint가 실행 가능하다
9. hook point 미발견 시 fail-closed 동작한다
