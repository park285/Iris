# STEP 06. SSE 재연결 이벤트 유실 race 수정

우선순위: **P0**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 구현 반영 완료, Kotlin/Gradle 검증 보류
- 구현 근거: `app/src/main/java/party/qwer/iris/SseEventBus.kt`, `app/src/main/java/party/qwer/iris/http/MemberRoutes.kt`
- 검증 근거: `app/src/test/java/party/qwer/iris/SseEventBusTest.kt`
- 메모: `openSubscriberWithReplaySuspend()`로 replay 계산과 subscriber 등록을 actor 내부 단일 command로 묶는 방향이 반영되었습니다.

## 1. 목적

현재 SSE 연결 재개 시 replay를 먼저 하고 subscriber를 나중에 엽니다. 그 사이 발생한 이벤트가 replay에도 live channel에도 들어가지 않는 유실 창이 있습니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/SseEventBus.kt`
- `app/src/main/java/party/qwer/iris/http/MemberRoutes.kt`

## 3. 확인된 위치

- SseEventBus.kt:190 — openSubscriberChannelSuspend()
- MemberRoutes.kt:76 — replayEnvelopesSuspend(lastEventId)
- MemberRoutes.kt:78 — openSubscriberChannelSuspend()

## 4. 현재 문제

MemberRoutes는 replay를 먼저 쓰고 그 뒤 live subscriber channel을 엽니다. replay와 subscribe 사이에 새 이벤트가 emit되면 해당 이벤트는 replay에도 없고 live channel에도 없습니다. Last-Event-ID 기반 재연결에서 가장 피해야 할 유실 창입니다.

## 5. 수정 방향

subscriber 등록과 replay 계산을 actor 내부 단일 command로 처리합니다. 외부 라우트가 replay 후 subscribe를 직접 조합하지 않게 하고, `openSubscriberWithReplaySuspend(afterId)` 하나로 처리합니다. 중복 가능성은 event id 기준으로 방어합니다.

## 6. 구현 절차

- [ ] `SseCommand.OpenSubscriberWithReplay` 추가
- [ ] `SubscriberReplay(replay, channel)` 자료 구조 추가
- [ ] actor 내부에서 subscriber 등록과 replay 계산을 같은 turn에서 처리
- [ ] MemberRoutes에서 기존 replay/open channel 조합 제거
- [ ] 라우트에서 `lastWrittenEventId` 기준 중복 방어
- [ ] persistent store와 memory buffer 양쪽 테스트

## 7. 코드 레벨 변경안

```kotlin
data class OpenSubscriberWithReplay(
    val afterId: Long,
    val reply: CompletableDeferred<SubscriberReplay>,
) : SseCommand

internal data class SubscriberReplay(
    val replay: List<SseEventEnvelope>,
    val channel: Channel<SseEventEnvelope>,
)
```

```kotlin
val opened = bus.openSubscriberWithReplaySuspend(lastEventId)
val channel = opened.channel
var lastWrittenEventId = lastEventId

opened.replay.forEach { envelope ->
    if (envelope.id > lastWrittenEventId) {
        writeStringUtf8(formatSseFrame(envelope))
        lastWrittenEventId = envelope.id
    }
}
flush()
```

## 8. 테스트 계획

- [ ] 재연결 중 event emit을 강제로 끼워 넣는 race 테스트
- [ ] replay/live 중복 id가 한 번만 write되는지 테스트
- [ ] replay window miss 처리 테스트
- [ ] persistent store replay 테스트

## 9. 문서화 반영

운영 문서에 SSE 재연결 중 이벤트 유실을 막기 위해 subscriber 등록과 replay 계산을 원자적으로 처리한다고 적습니다. 클라이언트는 id 기준 중복 제거를 해야 한다고 안내합니다.

## 10. 완료 기준

- replay와 subscribe 사이의 유실 창이 사라진다.
- 중복 이벤트는 id 기준으로 방어된다.
- Last-Event-ID 재연결 계약이 문서화된다.

## 11. 주의할 리스크

- store 기반 replay와 live channel 등록 순서 때문에 중복이 생길 수 있습니다.
- 중복 제거를 반드시 넣어야 합니다.
