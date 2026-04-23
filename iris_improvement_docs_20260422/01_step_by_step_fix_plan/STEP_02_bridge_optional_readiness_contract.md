# STEP 02. bridge optional/readiness 계약 정리

우선순위: **P0**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 구현 반영 완료, Kotlin/Gradle 검증 보류
- 구현 근거: `app/src/main/java/party/qwer/iris/http/HealthRoutes.kt`, `app/src/main/java/party/qwer/iris/ConfigManager.kt`, `README.md`
- 검증 근거: `app/src/test/java/party/qwer/iris/http/HealthRoutesTest.kt`, `app/src/test/java/party/qwer/iris/ConfigManagerStateTest.kt`
- 메모: 원안의 "기본 false" 제안 대신, 현재 구현은 `IRIS_REQUIRE_BRIDGE` 명시값을 우선하고 미설정/오류 값에서는 `bridgeToken` 또는 `IRIS_BRIDGE_TOKEN` 존재 여부로 auto 판정합니다.

## 1. 목적

README는 bridge를 선택 기능처럼 설명하지만, 코드의 readiness는 bridge token과 bridge health를 필수 조건처럼 다룹니다. 텍스트 전용 모드가 실제로 가능한지 계약을 분리해야 합니다.

## 2. 대상 파일

- `README.md`
- `app/src/main/java/party/qwer/iris/http/HealthRoutes.kt`
- `app/src/main/java/party/qwer/iris/ConfigManager.kt`
- `app/src/main/java/party/qwer/iris/AppRuntime.kt`

## 3. 확인된 위치

- HealthRoutes.kt:35 — RuntimeConfigReadiness
- HealthRoutes.kt:47 — bridge token not configured
- HealthRoutes.kt:97 — bridge not ready
- ConfigManager.kt:194 — runtimeConfigReadiness()

## 4. 현재 문제

문서상 bridge는 이미지/마크다운 전송용 선택 모듈처럼 설명됩니다. 하지만 `RuntimeConfigReadiness.bootstrapState()`는 bridge token이 없으면 blocked를 반환할 수 있습니다. 또한 `/ready`는 bridge health가 들어오면 bridge readiness를 요구합니다.

이 구조에서는 텍스트 웹훅만 쓰는 사용자가 bridge를 설치하지 않아도 된다는 문서 설명이 깨집니다. 운영 환경에서는 `/ready` 실패가 프로세스 재시작, 트래픽 차단, watchdog 오판으로 이어질 수 있습니다.

## 5. 수정 방향

bridge의 필요 여부를 런타임 설정으로 분리합니다. 기본값은 텍스트 전용 사용자를 고려해 `false`가 좋습니다. 이미지/확장 메시지 기능을 반드시 써야 하는 환경에서는 `IRIS_REQUIRE_BRIDGE=true`를 설정해 bridge token과 bridge health를 readiness 필수 조건으로 넣습니다.

## 6. 구현 절차

- [ ] `RuntimeConfigReadiness`에 `bridgeRequired: Boolean` 필드를 추가합니다.
- [ ] `bootstrapState()`에서 `bridgeRequired && !bridgeTokenConfigured`일 때만 차단합니다.
- [ ] `readinessFailureReason()`에서 bridge health 검사도 `bridgeRequired`일 때만 실패 처리합니다.
- [ ] `ConfigManager.runtimeConfigReadiness()`에서 `IRIS_REQUIRE_BRIDGE` 또는 config 값을 읽어 반영합니다.
- [ ] README의 bridge 설명과 `/ready` 설명을 함께 수정합니다.

## 7. 코드 레벨 변경안

```kotlin
internal data class RuntimeConfigReadiness(
    val inboundSigningSecretConfigured: Boolean,
    val outboundWebhookTokenConfigured: Boolean,
    val botControlTokenConfigured: Boolean,
    val bridgeTokenConfigured: Boolean,
    val defaultWebhookEndpointConfigured: Boolean,
    val bridgeRequired: Boolean,
) {
    fun bootstrapState(): RuntimeBootstrapState =
        when {
            !inboundSigningSecretConfigured ->
                RuntimeBootstrapState.Blocked("inbound signing secret not configured")

            !outboundWebhookTokenConfigured ->
                RuntimeBootstrapState.Blocked("outbound webhook token not configured")

            !botControlTokenConfigured ->
                RuntimeBootstrapState.Blocked("bot control token not configured")

            bridgeRequired && !bridgeTokenConfigured ->
                RuntimeBootstrapState.Blocked("bridge token not configured")

            !defaultWebhookEndpointConfigured ->
                RuntimeBootstrapState.Blocked("webhook endpoint not configured")

            else -> RuntimeBootstrapState.Ready
        }
}
```

```kotlin
internal fun readinessFailureReason(
    bridgeHealth: ImageBridgeHealthResult?,
    configReadiness: RuntimeConfigReadiness? = null,
): String? {
    when (val bootstrapState = configReadiness?.bootstrapState()) {
        null, RuntimeBootstrapState.Ready -> Unit
        is RuntimeBootstrapState.Blocked ->
            return "config not ready: ${bootstrapState.reason}"
    }

    val bridgeRequired = configReadiness?.bridgeRequired == true
    if (bridgeRequired && bridgeHealth != null && !isBridgeReady(bridgeHealth)) {
        return "bridge not ready"
    }

    return null
}
```

## 8. 테스트 계획

- [ ] bridge optional 모드에서 bridge token이 없어도 `/ready` 성공
- [ ] bridge required 모드에서 bridge token이 없으면 `/ready` 503
- [ ] bridge required 모드에서 bridge health 실패 시 `/ready` 503
- [ ] bridge optional 모드에서 텍스트 webhook delivery 동작 확인

## 9. 문서화 반영

README에 `IRIS_REQUIRE_BRIDGE`를 추가하고, bridge가 선택 사항인 조건과 필수 사항인 조건을 분리해서 적습니다. `/ready` 문서에는 bridge 검사가 `IRIS_REQUIRE_BRIDGE=true`일 때만 필수라고 명시합니다.

## 10. 완료 기준

- 텍스트 전용 모드와 bridge 필수 모드가 테스트로 구분된다.
- README, env 설명, readiness 코드가 같은 정책을 말한다.
- bridge optional 모드에서 readiness가 거짓 실패를 내지 않는다.

## 11. 주의할 리스크

- 기존 운영자가 bridge failure를 readiness failure로 기대하고 있었다면 기본값 변경이 동작 변화를 만들 수 있습니다.
- 기존 동작 보존이 필요하면 기본값을 `true`로 두고 README에서 명시해야 합니다.
