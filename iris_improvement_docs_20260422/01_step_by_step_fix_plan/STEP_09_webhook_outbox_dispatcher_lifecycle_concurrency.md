# STEP 09. Webhook outbox dispatcher 생명주기 동시성 수정

우선순위: **P1**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 완료
- 구현 근거: `app/src/main/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcher.kt`
- 검증 근거: `app/src/test/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcherTest.kt`, `./gradlew app:testDebugUnitTest --tests 'party.qwer.iris.delivery.webhook.WebhookOutboxDispatcherTest'`

## 1. 목적

`start()`가 동시 호출되면 polling job이 여러 개 생길 수 있습니다. dispatcher 생명주기 상태를 lock으로 보호해야 합니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcher.kt`

## 3. 확인된 위치

- WebhookOutboxDispatcher.kt:78 — start()

## 4. 현재 문제

`start()`는 active job 확인과 새 job 할당이 원자적이지 않습니다. 두 호출이 동시에 들어오면 둘 다 loop를 만들 수 있습니다.

## 5. 수정 방향

`lifecycleLock`을 추가해 `start()`와 `closeSuspend()`가 같은 lock으로 `pollingJob`, `shuttingDown` 상태를 다루게 합니다. `partitionCount`는 조용히 보정하지 말고 생성자에서 reject합니다.

## 6. 구현 절차

- [ ] lifecycleLock 추가
- [ ] start active check와 job 할당을 lock 안에서 수행
- [ ] closeSuspend에서 job detach는 lock 안, cancel/join은 lock 밖
- [ ] partitionCount > 0 require 추가

## 7. 코드 레벨 변경안

```kotlin
private val lifecycleLock = Any()

fun start() {
    synchronized(lifecycleLock) {
        if (pollingJob?.isActive == true) return
        shuttingDown = false
        recoverExpiredClaimsNow()
        pollingJob = coroutineScope.launch { pollingLoop() }
    }
}
```

## 8. 테스트 계획

- [ ] 동시 start 호출 테스트
- [ ] start-close-start 반복 테스트
- [ ] partitionCount 0 실패 테스트

## 9. 문서화 반영

운영 문서에 dispatcher는 singleton lifecycle이며 중복 start가 내부 loop 중복으로 이어지지 않는다고 적습니다.

## 10. 완료 기준

- 동시 start에도 polling loop가 하나만 존재한다.
- close 중 start가 들어와도 상태가 꼬이지 않는다.

## 11. 주의할 리스크

- lock 안에서 suspend 함수를 호출하지 않습니다. cancel/join은 lock 밖에서 해야 합니다.
