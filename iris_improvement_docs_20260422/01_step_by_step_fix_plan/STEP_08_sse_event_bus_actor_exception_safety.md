# STEP 08. SseEventBus actor 예외 안정성 강화

우선순위: **P0**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 구현 반영 완료, Kotlin/Gradle 검증 보류
- 구현 근거: `app/src/main/java/party/qwer/iris/SseEventBus.kt`
- 검증 근거: `app/src/test/java/party/qwer/iris/SseEventBusTest.kt`
- 메모: store insert/replay/maxId 실패 시 hanging 대신 fallback 또는 exception completion을 보장하는 방향으로 정리되었습니다.

## 1. 목적

SSE actor 내부에서 store 예외가 발생하면 command reply가 완료되지 않아 호출자가 무한 대기할 수 있습니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/SseEventBus.kt`

## 3. 확인된 위치

- SseEventBus.kt:260 — runActor()
- SseEventBus.kt:391 — dispatchSuspend()

## 4. 현재 문제

`SseEventBus` actor는 command를 순차 처리하지만 command 처리 중 예외가 발생했을 때 reply를 완료하는 공통 방어가 부족합니다. store insert/replay/maxId 예외가 actor를 죽이면 호출자는 `reply.await()`에서 끝나지 않을 수 있습니다.

## 5. 수정 방향

actor loop는 command별 try/catch를 적용하고, 실패한 command에 대해서도 반드시 reply를 complete 또는 completeExceptionally 처리해야 합니다. store 계층 오류는 로그로 남기고 command별 안전 fallback을 반환합니다.

## 6. 구현 절차

- [ ] `handleCommand(command)`로 command 처리 분리
- [ ] `runActor()` for-loop에서 try/catch 적용
- [ ] `failCommand(command, error)` 구현
- [ ] `dispatchSuspend()`에서 `trySend()`와 actor closed fallback 적용
- [ ] store maxId 초기화 실패 시 0 fallback과 로그 기록

## 7. 코드 레벨 변경안

```kotlin
for (command in commands) {
    try {
        handleCommand(command)
    } catch (error: Throwable) {
        IrisLogger.error("[SseEventBus] actor command failed: ${error.message}", error)
        failCommand(command, error)
    }
}
```

```kotlin
private fun failCommand(command: SseCommand, error: Throwable) {
    when (command) {
        is SseCommand.Emit -> command.reply.completeExceptionally(error)
        is SseCommand.Replay -> command.reply.complete(emptyList())
        is SseCommand.SubscriberCount -> command.reply.complete(0)
        is SseCommand.Close -> command.reply.complete(Unit)
        else -> Unit
    }
}
```

## 8. 테스트 계획

- [ ] fake store insert 예외 테스트
- [ ] fake store replay 예외 테스트
- [ ] actor close 이후 fallback 테스트
- [ ] maxId 초기화 실패 테스트

## 9. 문서화 반영

개발 문서에 actor command 추가 시 reply 완료 정책을 반드시 추가해야 한다고 적습니다.

## 10. 완료 기준

- store 예외 발생 시 호출자가 무한 대기하지 않는다.
- actor command 추가 시 실패 처리 정책이 명시된다.

## 11. 주의할 리스크

- fallback을 너무 관대하게 두면 실제 데이터 손실을 숨길 수 있습니다.
- emit 실패처럼 중요한 경로는 예외를 반환해야 합니다.
