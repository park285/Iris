# Session Chain Graft 실측 검증

> 작성일: 2026-03-25
> 범위: KR live 환경에서 image `/reply` 경로의 실제 session/thread 전달 지점 확인
> 관련 문서: `2026-03-25-session-chain-graft-design.md`

---

## 요약

실측 결과, image `/reply` 경로에서 `threadId/threadScope`를 살려서 `ChatSendingLogRequest$a.u(...)`까지 전달할 수 있는 실제 브리지 지점은 다음과 같다.

1. `BaseIntentFilterActivity.b6(...)`
   - 원본 `Intent`와 nested send intent에서 `sessionId/threadId/threadScope/roomId`를 관찰 가능
   - 같은 시점의 URI 목록으로 fingerprint를 계산할 수 있음
2. `ChatRoomFragment.cf(...)`
   - room/chatroom 문맥 안으로 들어온 이후의 마지막 안정적 image share 진입점
   - 이 시점에는 `forwardExtra`가 이미 `null`이며, `attachment`는 `{"callingPkg":"com.kakao.talk"}`만 남음
3. `bh.c.t(...)`
   - `ChatRoomFragment.i9()`가 반환하는 실제 runtime sender class는 `bh.c`
   - 이 메서드는 URI list를 인자로 받으므로 `b6`에서 저장한 fingerprint로 session을 다시 복원할 수 있음
4. `bh.c.A(ChatSendingLog, ...)`
   - `ChatSendingLog`가 이미 생성된 뒤지만 아직 `u()` 호출 전인 지점
   - 여기서 `sendingLog.threadId/scope`를 세팅하면 최종 `u()`에서 `threadId=... scope=2`가 보임

따라서 image graft의 실전 경로는 더 이상 `forwardExtra`에 의존하면 안 된다.

정답은:

`b6 fingerprint 저장 -> bh.c.t(...)에서 session 복원 -> bh.c.A(...)에서 ChatSendingLog 주입`

---

## 검증 환경

- KR host: `100.100.1.4`
- Iris HTTP port: `3000`
- KakaoTalk process: live attach 시점 기준 `pidof com.kakao.talk = 1720777`
- 검증 요청:
  - room: `18478615493603057`
  - threadId: `3804041011037167620`
  - threadScope: `2`
- 검증 payload:
  - `/reply`
  - `type=image`
  - 모바일에서 렌더 가능한 4x4 PNG base64

모든 검증 요청은 `202 Accepted`를 반환했다.

---

## 단계별 확인 결과

### 1. `BaseIntentFilterActivity.b6(...)`

다음 값이 그대로 관찰됐다.

- `action=android.intent.action.SEND_MULTIPLE`
- `type=image/*`
- `identifier=iris:<sessionId>`
- `sessionId`
- `threadId`
- `threadScope=2`
- `roomId`

또한 nested send intent에도 같은 session 정보가 보였다.

즉, Iris가 만든 custom session extras는 Kakao share ingress 초입에서는 살아 있다.

### 2. `BaseIntentFilterActivity.Z5(...)`

`forward intent`로 넘어가면서 다음 현상이 확인됐다.

- `identifier/sessionId/threadId/roomId`는 복사 가능
- `threadScope`는 `0`으로 약화됨
- nested 쪽은 `null` 또는 `session=none`
- `forwardExtra` 기반 전달은 신뢰할 수 없음

즉, `Z5`는 일부 정보는 유지되지만 thread metadata 전체를 보존하는 안정 경로는 아니다.

### 3. `TaskRootActivity.c5(...)`

`TaskRootActivity.c5(...)` 자체는 wrapper intent만 다루며 `session=none`으로 보였다.

하지만 `Z5.forward`에 identifier/session을 복사한 뒤에는 `ChatRoomHolderActivity.onCreate(...)`에서 다시 해당 session을 볼 수 있었다.

즉, `TaskRootActivity`는 직접 session을 쓰지 않지만 fresh-open 경로를 막지는 않는다.

### 4. `ChatRoomHolderActivity.onCreate(...)`

다음 값이 관찰됐다.

- `identifier=iris:<sessionId>`
- `sessionId`
- `threadId`
- `roomId`

따라서 fresh-open 경로는 `ChatRoomHolderActivity` 진입 시점까지 session을 유지할 수 있다.

### 5. `ChatRoomFragment.cf(...)`

여기가 중요하다.

실측 결과:

- `uris=1`
- `type=Photo`
- `writeType=Connect`
- `attachment={"callingPkg":"com.kakao.talk"}`
- `forwardExtra=null`
- `sessionId`는 fragment intent에서는 보이지만, outgoing payload 쪽에는 직접 실리지 않음

결론:

- image `/reply` 경로는 `forwardExtra`를 이용한 thread 전달이 실제로 동작하지 않는다
- session 전달은 intent payload가 아니라 다른 키가 필요하다

### 6. `ChatRoomFragment.i9()`의 실제 sender class

runtime reflection으로 확인한 결과:

- `ChatRoomFragment.i9()` 반환 class: `bh.c`

주요 메서드:

- `o(List, Op.c, String, JSONObject, JSONObject, ChatSendingLogRequest$c, boolean, boolean, Function1, Function1)`
- `p(List, Op.c, String, JSONObject, JSONObject, ChatSendingLogRequest$c, boolean, boolean, m)`
- `t(bh.c, JSONObject, Op.c, JSONObject, String, boolean, boolean, ChatSendingLogRequest$c, m, List)`
- `u(bh.c, JSONObject, JSONObject, String, ChatSendingLogRequest$c, m, boolean, List)`
- `A(ChatSendingLog, ChatSendingLogRequest$c, m)`
- `B(bh.c, ChatSendingLog, ChatSendingLogRequest$c, m, int, Object)`

즉 실제 media sender hook target은 `mf.Z3`가 아니라 `bh.c`다.

### 7. `bh.c.o(...)`

실측 결과:

- `uris`는 그대로 보임
- `attachment={"callingPkg":"com.kakao.talk"}`
- `forwardExtra=null`
- `session`은 URI fingerprint로만 다시 연결 가능

즉 `o(...)`에서도 `forwardExtra`는 이미 죽어 있다.

### 8. `bh.c.t(...)`

`t(...)`는 `List`를 인자로 받는다.

이 `List`의 URI fingerprint는 `b6(...)`에서 본 fingerprint와 동일했다.

따라서:

- `b6(...)`에서 `fingerprint -> original SessionMeta(threadScope=2 포함)` 저장
- `t(...)`에서 `fingerprint -> SessionMeta` 복원

이 경로가 실제로 성립한다.

### 9. `bh.c.A(ChatSendingLog, ...)`

이 지점에서 `current session`이 살아 있는 상태로 `ChatSendingLog`를 직접 수정할 수 있었다.

실측 주입:

- room 일치 확인
- `sendingLog.threadId = 3804041011037167620`
- `sendingLog.scope = 2`

그리고 직후 `u.observe` 결과:

- `threadId=3804041011037167620`
- `scope=2`

즉 `A(...)`는 최종 실전 주입 지점으로 사용할 수 있다.

---

## room 기반 blocking 로직에 대한 의미

이제 “image reply는 room 기반으로만 판단해야 한다”는 전제는 깨졌다.

이유:

- image `/reply` 경로에서도 thread metadata를 최종 `ChatSendingLog`까지 넣을 수 있음이 실측으로 확인됨
- 따라서 차단 기준은 `room` 자체가 아니라 `thread delivery capability`여야 함

새 기준은 다음이 맞다.

1. `threadId/threadScope`가 필요한 image send인가
2. 현재 graft readiness가 확보됐는가
3. `b6 fingerprint -> bh.c.t -> bh.c.A` 경로가 활성화됐는가

이 세 가지가 참이면 허용한다.

즉 blocking 로직은:

- 기존: `image + room/thread 조건 -> room 기반 차단`
- 변경: `image + thread metadata 필요 + graft not ready -> 차단`

로 바뀌어야 한다.

---

## 다음 구현 단계

### 1. discovery 코드에서 production 코드로 승격

현재는 discovery agent 안에서 proof-of-fix를 확인한 상태다.

정식 구현으로 옮길 때는:

- `b6(...)`에서 `fingerprint -> SessionMeta` 저장
- `bh.c.t(...)`에서 복원
- `bh.c.A(...)`에서 실제 주입
- `u()`는 최종 확인/중복 방지/`callingPkg` 정리 용도로 유지

### 2. fingerprint session store 정리

현재 필요한 키:

- ordered URI fingerprint
- sessionId
- threadId
- threadScope
- roomId
- createdAt

TTL 짧게 유지하고, 같은 fingerprint의 downgrade(`scope=2 -> scope=0`)는 덮어쓰지 않도록 해야 한다.

### 3. blocking/admission 변경

server/app 쪽 blocking 로직은 room 기반에서 readiness 기반으로 변경한다.

핵심 원칙:

- graft 준비 완료: 허용
- graft 준비 불가: fail-closed
- ambiguous room fallback: 기본 금지

---

## 결론

실측 결과, image `/reply` 경로의 실제 thread graft 정답은 다음과 같이 확정됐다.

`BaseIntentFilterActivity.b6(...)`에서 원본 session을 URI fingerprint로 보존하고, `bh.c.t(...)`에서 복원한 뒤, `bh.c.A(ChatSendingLog, ...)`에서 threadId/scope를 주입한다.`

이 경로가 검증됐으므로, 이제 room 기반 blocking 로직을 readiness 기반으로 바꿀 수 있는 기술적 근거가 확보됐다.
