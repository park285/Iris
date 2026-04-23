# STEP 22. SqliteSseEventStore prune 입력 검증

우선순위: **P2**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 완료
- 구현 근거: `app/src/main/java/party/qwer/iris/persistence/SqliteSseEventStore.kt`
- 검증 근거: `app/src/test/java/party/qwer/iris/persistence/SqliteSseEventStoreTest.kt`, `./gradlew app:testDebugUnitTest --tests 'party.qwer.iris.persistence.SqliteSseEventStoreTest'`

## 1. 목적

`prune(keepCount)`가 keepCount를 SQL 문자열에 직접 넣습니다. 내부 값이라도 양수 검증은 해야 합니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/persistence/SqliteSseEventStore.kt`

## 3. 확인된 위치

- SqliteSseEventStore.kt — prune(keepCount)

## 4. 현재 문제

`keepCount`가 내부 정책값이라 SQL injection 가능성은 낮지만, 0이나 음수가 들어오면 예상하지 못한 삭제가 발생할 수 있습니다.

## 5. 수정 방향

`require(keepCount > 0)`를 추가합니다. driver가 parameterized execute를 지원하게 되면 bind 방식으로 바꿉니다.

## 6. 구현 절차

- [ ] prune 초반 require 추가
- [ ] 0/음수 테스트 추가
- [ ] 최신 N개 유지 테스트 추가

## 7. 코드 레벨 변경안

```kotlin
override fun prune(keepCount: Int) {
    require(keepCount > 0) { "keepCount must be positive" }
    // DELETE ...
}
```

## 8. 테스트 계획

- [ ] keepCount 0 실패 테스트
- [ ] keepCount -1 실패 테스트
- [ ] 최신 N개 유지 테스트

## 9. 문서화 반영

SSE retention 정책 문서에 replay window와 prune count의 의미를 적습니다.

## 10. 완료 기준

- 비정상 keepCount가 조용히 SQL로 들어가지 않는다.

## 11. 주의할 리스크

- 0을 모두 삭제 의미로 쓰고 있었다면 별도 메서드가 필요합니다.
