# STEP 13. ReplyAdmissionService actor 소유 상태 경계 수정

우선순위: **P1**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 완료
- 구현 근거: `app/src/main/java/party/qwer/iris/reply/ReplyAdmissionService.kt`
- 검증 근거: `app/src/test/java/party/qwer/iris/reply/ReplyAdmissionServiceTest.kt`, `./gradlew app:testDebugUnitTest --tests 'party.qwer.iris.reply.ReplyAdmissionServiceTest'`

## 1. 목적

`ReplyAdmissionService`는 actor가 소유해야 할 mutable state를 shutdown 경로에서 actor 밖에서 직접 수정합니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/reply/ReplyAdmissionService.kt`

## 3. 확인된 위치

- ReplyAdmissionService.kt:120 — workerRegistry
- ReplyAdmissionService.kt:178 — shutdownSuspend()
- ReplyAdmissionService.kt:191 — closingWorkers.clear()

## 4. 현재 문제

`workerRegistry`, `closingWorkers`, `lifecycle`은 actor가 관리해야 하는 상태입니다. shutdown 경로에서 actor 밖에서 `closingWorkers.clear()`를 호출하면 actor 모델의 경계를 깹니다.

## 5. 수정 방향

actor 소유 상태는 actor command 안에서만 변경합니다. shutdown 후 정리도 `ShutdownCompleted` command를 통해 actor가 처리하도록 바꿉니다.

## 6. 구현 절차

- [ ] AdmissionCommand.ShutdownCompleted 추가
- [ ] handleShutdownCompleted에서 closingWorkers.clear 수행
- [ ] shutdownSuspend에서 worker close 후 command dispatch
- [ ] actor closed fallback 정책 정리

## 7. 코드 레벨 변경안

```kotlin
data class ShutdownCompleted(
    val reply: CompletableDeferred<Unit>,
) : AdmissionCommand

private fun handleShutdownCompleted(command: AdmissionCommand.ShutdownCompleted) {
    closingWorkers.clear()
    command.reply.complete(Unit)
}
```

## 8. 테스트 계획

- [ ] shutdown 중 worker closed event 테스트
- [ ] actor closed fallback 테스트
- [ ] debug snapshot actor state 테스트

## 9. 문서화 반영

개발 문서에 actor-owned mutable state는 actor command 밖에서 직접 수정하지 않는다고 적습니다.

## 10. 완료 기준

- actor-owned mutable state가 actor 밖에서 직접 변경되지 않는다.
- shutdown 경로에서도 상태 변경 순서가 예측 가능하다.

## 11. 주의할 리스크

- command 추가 시 handler 누락을 막기 위해 sealed command exhaustiveness를 활용합니다.
