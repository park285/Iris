# STEP 11. SQLite statement close 누락 수정

우선순위: **P1**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 완료
- 구현 근거: `app/src/main/java/party/qwer/iris/persistence/AndroidSqliteDriver.kt`
- 검증 근거: `app/src/test/java/party/qwer/iris/persistence/AndroidSqliteDriverTest.kt`, `./gradlew app:testDebugUnitTest --tests 'party.qwer.iris.persistence.AndroidSqliteDriverTest'`

## 1. 목적

`AndroidSqliteDriver.update()`에서 `SQLiteStatement`를 닫지 않습니다. 빈번한 update 경로에서 리소스 누수가 발생할 수 있습니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/persistence/AndroidSqliteDriver.kt`

## 3. 확인된 위치

- AndroidSqliteDriver.kt:47 — update()

## 4. 현재 문제

`compileStatement(sql)`로 만든 statement는 사용 후 닫아야 합니다. outbox, checkpoint, SSE store처럼 update가 자주 일어나는 경로에서 누수 가능성이 있습니다.

## 5. 수정 방향

`database.compileStatement(sql).use { stmt -> ... }` 형태로 바꿉니다. 예외 발생 시에도 close되도록 보장합니다.

## 6. 구현 절차

- [ ] update 구현에 use 적용
- [ ] bind 로직 유지
- [ ] executeUpdateDelete 후 자동 close

## 7. 코드 레벨 변경안

```kotlin
database.compileStatement(sql).use { stmt ->
    // bind args
    return stmt.executeUpdateDelete()
}
```

## 8. 테스트 계획

- [ ] 정상 update 테스트
- [ ] 예외 발생 시 close 호출 fake 테스트
- [ ] 반복 update stress 테스트

## 9. 문서화 반영

개발 문서에 closeable resource는 `use`로 감싸는 규칙을 추가합니다.

## 10. 완료 기준

- 모든 update 경로에서 SQLiteStatement가 닫힌다.
- 예외 발생 시에도 statement가 닫힌다.

## 11. 주의할 리스크

- AutoCloseable 지원 여부를 Android target에서 확인해야 합니다.
