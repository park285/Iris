# STEP 04. config 파일 로드 validation 강화

우선순위: **P0**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 구현 반영 완료, Kotlin/Gradle 검증 보류
- 구현 근거: `app/src/main/java/party/qwer/iris/config/ConfigPolicy.kt`
- 검증 근거: `app/src/test/java/party/qwer/iris/config/ConfigPolicyTest.kt`, `app/src/test/java/party/qwer/iris/config/ConfigPersistenceTest.kt`, `app/src/test/java/party/qwer/iris/ConfigManagerPersistenceTest.kt`
- 메모: endpoint scheme, route key, secret whitespace/control-character 검증이 file load 경로까지 확대된 상태로 보입니다.

## 1. 목적

API를 거치지 않고 파일에 직접 들어온 설정값은 일부만 검증됩니다. webhook URL, route, secret 공백/제어문자 검증을 파일 로드 경로에도 적용해야 합니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/config/ConfigPolicy.kt`
- `app/src/main/java/party/qwer/iris/ConfigPersistence.kt`
- `app/src/main/java/party/qwer/iris/UserConfigState.kt`

## 3. 확인된 위치

- ConfigPolicy.kt:132 — validate(state)
- ConfigPolicy.kt:153 — validateWebhookEndpoint(endpoint)

## 4. 현재 문제

현재 `ConfigPolicy.validate(state)`는 field policy 몇 개만 검사합니다. 포트, polling rate, message send rate, jitter 같은 수치 설정은 검증되지만, `endpoint`, `webhooks`, route prefix, secret/token 형태는 파일 로드 경로에서 충분히 검증되지 않을 수 있습니다.

API를 통해 설정을 바꿀 때는 endpoint validation을 타더라도, 사용자가 config 파일을 직접 수정하거나 배포 자동화가 파일을 생성하는 경우에는 잘못된 URL이나 route가 그대로 들어올 수 있습니다.

## 5. 수정 방향

모든 설정 진입점에서 같은 validation 기준을 쓰도록 만듭니다. API 요청 검증과 파일 로드 검증이 갈라지면 운영 장애가 반복됩니다. `ConfigPolicy.validate(state)`를 전체 상태 검증 함수로 확장합니다.

## 6. 구현 절차

- [ ] `endpoint`가 비어 있지 않다면 URL 형식을 검증합니다.
- [ ] `webhooks` map의 key는 canonical route로 정규화한 뒤 route 규칙을 검사합니다.
- [ ] `webhooks` map의 value는 endpoint URL 형식을 검사합니다.
- [ ] `commandRoutePrefixes`, `imageMessageTypeRoutes`의 route와 prefix를 검사합니다.
- [ ] secret/token 값은 앞뒤 공백과 제어문자를 금지합니다.
- [ ] 빈 route를 default route로 볼지, 잘못된 route로 볼지 정책을 확정합니다.

## 7. 코드 레벨 변경안

```kotlin
fun validate(state: UserConfigState): List<ConfigValidationError> =
    buildList {
        fieldPolicies.mapNotNullTo(this) { policy ->
            policy.validate(state)?.let { ConfigValidationError(policy.field, it) }
        }

        validateWebhookEndpoint(state.endpoint.trim())?.let { message ->
            add(ConfigValidationError(ConfigField.ROUTING_POLICY, "endpoint: $message"))
        }

        state.webhooks.forEach { (route, endpoint) ->
            val normalizedRoute = canonicalWebhookRoute(route)
            validateWebhookRoute(normalizedRoute)?.let { message ->
                add(ConfigValidationError(ConfigField.ROUTING_POLICY, "webhooks.$route: $message"))
            }

            validateWebhookEndpoint(endpoint.trim())?.let { message ->
                add(ConfigValidationError(ConfigField.ROUTING_POLICY, "webhooks.$route: $message"))
            }
        }

        validateSecret("inboundSigningSecret", state.inboundSigningSecret)?.let {
            add(ConfigValidationError(ConfigField.INBOUND_SIGNING_SECRET, it))
        }
    }
```

```kotlin
private fun validateSecret(name: String, value: String): String? {
    if (value.isEmpty()) return null
    if (value != value.trim()) return "$name must not have leading or trailing whitespace"
    if (value.any { it.isISOControl() }) return "$name must not contain control characters"
    return null
}
```

## 8. 테스트 계획

- [ ] config 파일에 `ftp://...` endpoint를 넣고 load 실패 확인
- [ ] config 파일에 공백 포함 route를 넣고 load 실패 확인
- [ ] secret 앞뒤 공백이 있는 파일을 load했을 때 명확히 거부되는지 확인
- [ ] API 경로와 파일 로드 경로가 같은 validation error를 내는지 확인

## 9. 문서화 반영

README에 config 파일 검증 항목을 적습니다. route 이름 규칙, URL 정책, secret/token 공백 금지 규칙을 문서화합니다.

## 10. 완료 기준

- 파일로 직접 들어온 잘못된 설정도 서버 시작 또는 load 시점에 명확히 거부된다.
- API 경로와 파일 로드 경로의 validation 기준이 같아진다.

## 11. 주의할 리스크

- 기존 운영 config에 앞뒤 공백이 숨어 있으면 업데이트 후 시작 실패가 날 수 있습니다.
- route 빈 문자열 정책을 바꾸면 기존 default route 표현과 충돌할 수 있습니다.
