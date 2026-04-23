# STEP 12. 종료 순서와 checkpoint flush 수정

우선순위: **P1**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 완료
- 구현 근거: `app/src/main/java/party/qwer/iris/ObserverHelper.kt`, `app/src/main/java/party/qwer/iris/ingress/CommandIngressService.kt`
- 검증 근거: `app/src/test/java/party/qwer/iris/ObserverHelperLogicTest.kt`, `app/src/test/java/party/qwer/iris/ingress/CommandIngressServiceTest.kt`

## 1. 목적

`ObserverHelper.close()`가 checkpoint를 먼저 flush하고 ingress를 나중에 닫습니다. 종료 중 마지막 checkpoint advance가 flush되지 않을 수 있습니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/ObserverHelper.kt`
- `app/src/main/java/party/qwer/iris/ingress/CommandIngressService.kt`
- `app/src/main/java/party/qwer/iris/RuntimeBuilders.kt`

## 3. 확인된 위치

- ObserverHelper.kt:37 — close()
- CommandIngressService.kt:139 — close()

## 4. 현재 문제

ingress dispatch loop는 처리 완료 시 checkpoint를 advance할 수 있습니다. checkpoint flush를 먼저 하면 close 과정에서 advance된 마지막 checkpoint가 저장되지 않을 수 있습니다.

## 5. 수정 방향

ingress를 먼저 닫고 dispatch loop 종료를 기다린 뒤 checkpoint를 flush합니다. `CommandIngressService.closeSuspend()`를 추가해 cancel 후 join까지 수행합니다.

## 6. 구현 절차

- [ ] closeSuspend 추가
- [ ] recheck job cancelAndJoin
- [ ] dispatch loop cancelAndJoin
- [ ] ObserverHelper close 순서 변경
- [ ] RuntimeBuilders shutdown 순서 확인

## 7. 코드 레벨 변경안

```kotlin
override fun close() {
    ingressService.close()
    checkpointJournal.flushNow()
}
```

## 8. 테스트 계획

- [ ] shutdown 직전 checkpoint flush 테스트
- [ ] close 반환 이후 dispatch job inactive 테스트
- [ ] shutdown step 순서 테스트

## 9. 문서화 반영

운영 문서에 graceful shutdown 순서와 강제 종료 리스크를 적습니다.

## 10. 완료 기준

- 종료 직전 처리 완료된 checkpoint가 flush된다.
- close 반환 시 dispatch loop가 종료되어 있다.

## 11. 주의할 리스크

- `runBlocking` 사용 위치는 신중해야 합니다. 가능하면 suspend close를 직접 호출합니다.
