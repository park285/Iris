# STEP 17. webhook endpoint default/route 정책 정리

우선순위: **P1**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 완료
- 구현 근거: `app/src/main/java/party/qwer/iris/http/HealthRoutes.kt`, `app/src/main/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcher.kt`, `README.md`
- 검증 근거: `app/src/test/java/party/qwer/iris/http/HealthRoutesTest.kt`, `app/src/test/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcherTest.kt`

## 1. 목적

route별 endpoint가 있어도 bootstrap이 default endpoint를 필수로 요구할 수 있습니다. default endpoint가 필수인지 fallback인지 정책을 확정해야 합니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcher.kt`
- `app/src/main/java/party/qwer/iris/ConfigManager.kt`
- `README.md`

## 3. 확인된 위치

- WebhookOutboxDispatcher.kt:235 — bootstrapFailureReason()
- WebhookOutboxDispatcher.kt:246 — defaultWebhookConfigured

## 4. 현재 문제

dispatcher bootstrap은 default webhook endpoint가 없으면 전체 dispatch를 막을 수 있습니다. 실제 처리에서는 route별 endpoint를 다시 조회하므로 정책이 불명확합니다.

## 5. 수정 방향

권장 방향은 default endpoint를 fallback으로 두는 것입니다. bootstrap은 secret류만 검사하고 endpoint 누락은 entry 처리 시 route별로 판단합니다.

## 6. 구현 절차

- [ ] default endpoint 필수 여부 결정
- [ ] route별 endpoint만 허용 시 bootstrap default 필수 조건 제거
- [ ] default 필수 유지 시 validation/README에서 강제
- [ ] route별 endpoint 누락은 entry 단위 reject

## 7. 코드 레벨 변경안

```kotlin
private fun bootstrapFailureReason(): String? {
    val inboundConfigured = config.activeInboundSigningSecret().isNotBlank()
    val outboundConfigured = config.activeOutboundWebhookToken().isNotBlank()
    val botControlConfigured = config.activeBotControlToken().isNotBlank()

    return when {
        !inboundConfigured -> "inbound signing secret not configured"
        !outboundConfigured -> "outbound webhook token not configured"
        !botControlConfigured -> "bot control token not configured"
        else -> null
    }
}
```

## 8. 테스트 계획

- [ ] route별 endpoint만 설정한 dispatch 성공 테스트
- [ ] route endpoint 누락 시 해당 entry REJECTED 테스트
- [ ] default endpoint fallback 테스트

## 9. 문서화 반영

README에 default endpoint가 필수인지 fallback인지 하나의 정책만 적습니다.

## 10. 완료 기준

- webhook endpoint 정책이 코드, README, 테스트에서 동일하다.
- route별 endpoint 구성에서 bootstrap이 전체 dispatch를 막지 않는다.

## 11. 주의할 리스크

- 기존 운영자가 default endpoint 필수 정책에 의존했다면 릴리스 노트가 필요합니다.
