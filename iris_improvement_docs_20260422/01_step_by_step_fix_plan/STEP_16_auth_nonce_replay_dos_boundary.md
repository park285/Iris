# STEP 16. 인증 nonce replay/DoS 경계 강화

우선순위: **P1**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 완료
- 구현 근거: `app/src/main/java/party/qwer/iris/RequestAuthenticator.kt`, `app/src/main/java/party/qwer/iris/NonceWindow.kt`
- 검증 근거: `app/src/test/java/party/qwer/iris/RequestAuthenticatorTest.kt`, `app/src/test/java/party/qwer/iris/http/AuthSupportTest.kt`, `./gradlew app:testDebugUnitTest --tests 'party.qwer.iris.RequestAuthenticatorTest' --tests 'party.qwer.iris.http.AuthSupportTest'`

## 1. 목적

nonce replay 방어는 대체로 잘 되어 있지만, 큰 body replay는 body read 이후에야 거부될 수 있습니다. 정책과 방어 수준을 명확히 해야 합니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/RequestAuthenticator.kt`

## 3. 확인된 위치

- RequestAuthenticator.kt:15 — SignaturePrecheck
- RequestAuthenticator.kt:129 — nonceWindow.tryRecord()

## 4. 현재 문제

현재 nonce는 body hash 검증 이후 기록됩니다. 유효 서명을 가진 큰 body replay가 동시에 들어오면 각 요청이 body를 읽은 뒤에야 nonce 충돌로 거부될 수 있습니다.

## 5. 수정 방향

nonce scope를 먼저 확정합니다. 부하 방어가 필요하면 precheck 성공 직후 reserve하고 body hash 성공 시 commit, 실패 시 release하는 2단계 구조를 씁니다.

## 6. 구현 절차

- [ ] nonce 전역/요청 단위 정책 확정
- [ ] nonce key 구성 문서화
- [ ] tryReserve/commit/release 도입 검토
- [ ] body hash mismatch 시 release 처리

## 7. 코드 레벨 변경안

```kotlin
data class SignaturePrecheck(...) {
    val nonceKey: String
        get() = "$method\n$path\n$timestampEpochMs\n$nonce"
}
```

## 8. 테스트 계획

- [ ] 동일 nonce 동시 요청 테스트
- [ ] body hash mismatch 후 nonce pending 정리 테스트
- [ ] timestamp window 경계 테스트

## 9. 문서화 반영

인증 계약 문서에 nonce scope를 명시합니다.

## 10. 완료 기준

- 동일 nonce 재사용 요청이 정책에 맞게 거부된다.
- 큰 body replay가 불필요하게 여러 번 body read를 유발하지 않는다.

## 11. 주의할 리스크

- reserve/commit 구조는 복잡도가 높습니다. 현재 body limit이 작다면 정책 문서화부터 할 수 있습니다.
