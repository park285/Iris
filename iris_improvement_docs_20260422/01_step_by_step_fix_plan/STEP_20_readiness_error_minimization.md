# STEP 20. readiness error 정보 노출 최소화

우선순위: **P2**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 완료
- 구현 근거: `app/src/main/java/party/qwer/iris/http/HealthRoutes.kt`, `app/src/main/java/party/qwer/iris/ConfigManager.kt`, `app/src/main/java/party/qwer/iris/IrisServer.kt`, `README.md`
- 검증 근거: `app/src/test/java/party/qwer/iris/http/HealthRoutesTest.kt`, `./gradlew app:testDebugUnitTest --tests 'party.qwer.iris.http.HealthRoutesTest'`

## 1. 목적

`/ready`가 인증 없이 내부 설정 실패 사유를 자세히 노출할 수 있습니다. 외부 bind 환경에서는 단순 응답이 안전합니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/http/HealthRoutes.kt`
- `README.md`

## 3. 확인된 위치

- HealthRoutes.kt:112 — get("/ready")

## 4. 현재 문제

`/ready`는 secret/token 설정 여부 같은 내부 상태를 응답으로 내보낼 수 있습니다. `IRIS_BIND_HOST=0.0.0.0` 운영에서는 외부 정보 노출이 됩니다.

## 5. 수정 방향

자세한 실패 사유는 로그에 남기고 HTTP 응답은 기본적으로 `not ready`로 단순화합니다. 개발 환경에서만 verbose 옵션을 둘 수 있습니다.

## 6. 구현 절차

- [ ] readyErrorMessage helper 추가
- [ ] 기본 응답은 not ready
- [ ] raw reason은 log 기록
- [ ] IRIS_READY_VERBOSE 개발용 옵션 검토

## 7. 코드 레벨 변경안

```kotlin
return if (verbose) rawReason else "not ready"
```

## 8. 테스트 계획

- [ ] verbose off 응답 테스트
- [ ] verbose on 응답 테스트
- [ ] 로그 raw reason 테스트

## 9. 문서화 반영

운영 문서에 health endpoint 외부 노출 주의사항과 verbose 옵션을 적습니다.

## 10. 완료 기준

- 외부에서 `/ready`를 호출해도 내부 설정 상태가 자세히 노출되지 않는다.

## 11. 주의할 리스크

- 모니터링이 상세 reason에 의존하면 알림 룰 수정이 필요합니다.
