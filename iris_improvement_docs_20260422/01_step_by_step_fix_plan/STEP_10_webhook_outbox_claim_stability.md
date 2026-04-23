# STEP 10. Webhook outbox claim 안정화

우선순위: **P1**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 완료
- 구현 근거: `app/src/main/java/party/qwer/iris/persistence/SqliteWebhookDeliveryStore.kt`
- 검증 근거: `app/src/test/java/party/qwer/iris/persistence/SqliteWebhookDeliveryStoreTest.kt`, `./gradlew app:testDebugUnitTest --tests 'party.qwer.iris.persistence.SqliteWebhookDeliveryStoreTest'`

## 1. 목적

claim 후보를 조회하는 cursor mapper 안에서 같은 테이블을 update합니다. 조회와 update를 분리하고 update 결과를 확인해야 합니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/persistence/SqliteWebhookDeliveryStore.kt`

## 3. 확인된 위치

- SqliteWebhookDeliveryStore.kt:9 — enqueue()
- SqliteWebhookDeliveryStore.kt:24 — claimReady()

## 4. 현재 문제

`claimReady()`는 SELECT mapper 안에서 같은 row를 update합니다. 이 패턴은 드라이버 동작에 의존하고 update 결과가 0이어도 claimed로 반환될 수 있습니다.

## 5. 수정 방향

후보 목록을 먼저 읽고, 각 후보에 대해 update를 수행한 뒤 update count가 1인 경우에만 `ClaimedDelivery`로 반환합니다. `enqueue()`의 `!!`도 명확한 예외로 바꿉니다.

## 6. 구현 절차

- [ ] ClaimCandidate 추가
- [ ] query mapper에서는 읽기만 수행
- [ ] update count 1인 row만 반환
- [ ] update count 0은 stale candidate로 로그 후 제외
- [ ] enqueue의 !! 제거

## 7. 코드 레벨 변경안

```kotlin
val updated = update(/* UPDATE ... WHERE id = ? AND status IN (...) */, listOf(...))
if (updated != 1) {
    IrisLogger.warn("[WebhookOutbox] stale claim candidate ignored: id=${candidate.id}")
    return@mapNotNull null
}
```

## 8. 테스트 계획

- [ ] 동시 claim 테스트
- [ ] 이미 CLAIMED인 row 재반환 방지 테스트
- [ ] update count 0 제외 테스트
- [ ] enqueue id 조회 실패 테스트

## 9. 문서화 반영

outbox delivery 상태 전이를 운영 문서에 설명합니다.

## 10. 완료 기준

- claim update 실패 row가 반환되지 않는다.
- 동일 delivery가 두 worker에 동시에 claim되지 않는다.

## 11. 주의할 리스크

- transaction 범위를 유지하지 않으면 race가 커집니다.
