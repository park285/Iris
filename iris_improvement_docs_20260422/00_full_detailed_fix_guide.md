# Iris 개선 전체 수정 가이드

대상 커밋: `cf9f4c9f939d7db38dd81f97d0d398b5a8156283`

작성 기준일: `2026-04-22`

## 현재 반영 상태

기준 시점: `2026-04-23` 현재 워크트리

- STEP 01 ~ STEP 08 관련 구현은 현재 워크트리에 반영되어 있습니다.
- STEP 02, STEP 03, STEP 04, STEP 06, STEP 07, STEP 08은 구현 근거와 관련 테스트 파일을 확인했지만 Kotlin/Gradle 재실행 근거는 이번 동기화에 포함하지 못했습니다.
- STEP 01은 closeout shell test, `BUNDLE_MANIFEST.txt`, 재생성 번들 export/verify 근거까지 확인했습니다.
- STEP 09 ~ STEP 22 관련 구현은 현재 워크트리에 반영되어 있습니다.


## 전체 방향

이 문서는 Iris 코드 리뷰에서 도출된 수정 항목을 실제 개발 작업으로 옮기기 위한 전체 실행 가이드입니다. 정상 경로보다 실패/재연결/종료/shell/입력 검증 경계를 중심으로 정리했습니다.


## 스텝 목록

- STEP 01. [P0] closeout 테스트 번들 무결성 복구
- STEP 02. [P0] bridge optional/readiness 계약 정리
- STEP 03. [P0] 설정 변경 API 원자성 보장
- STEP 04. [P0] config 파일 로드 validation 강화
- STEP 05. [P0] shell quoting 및 명령 주입 방어
- STEP 06. [P0] SSE 재연결 이벤트 유실 race 수정
- STEP 07. [P0] SSE frame multiline 안정화
- STEP 08. [P0] SseEventBus actor 예외 안정성 강화
- STEP 09. [P1] Webhook outbox dispatcher 생명주기 동시성 수정
- STEP 10. [P1] Webhook outbox claim 안정화
- STEP 11. [P1] SQLite statement close 누락 수정
- STEP 12. [P1] 종료 순서와 checkpoint flush 수정
- STEP 13. [P1] ReplyAdmissionService actor 소유 상태 경계 수정
- STEP 14. [P1] multipart 입력 처리 강화
- STEP 15. [P1] 요청 body size 계산 overflow 방어
- STEP 16. [P1] 인증 nonce replay/DoS 경계 강화
- STEP 17. [P1] webhook endpoint default/route 정책 정리
- STEP 18. [P1] Rust/Kotlin canonical query 계약 일치화
- STEP 19. [P2] APK checksum 검증 SHA-256 전환
- STEP 20. [P2] readiness error 정보 노출 최소화
- STEP 21. [P2] ConfigStateStore lock 구조 단순화
- STEP 22. [P2] SqliteSseEventStore prune 입력 검증



---

# STEP 01. closeout 테스트 번들 무결성 복구

우선순위: **P0**

## 1. 목적

현재 테스트가 참조하는 closeout 스크립트가 실제 번들에 없어 테스트가 즉시 실패합니다. 테스트와 번들의 자가완결성을 먼저 복구해야 합니다.

## 2. 대상 파일

- `tests/closeout_packet_scripts_test.sh`
- `scripts/replay_closeout.sh`
- `scripts/verify_closeout_packet.py`
- `scripts/closeout_facts.py`
- `BUNDLE_MANIFEST.txt`

## 3. 확인된 위치

- tests/closeout_packet_scripts_test.sh:12 — replay_closeout.sh 복사
- tests/closeout_packet_scripts_test.sh:13 — verify_closeout_packet.py 복사
- tests/closeout_packet_scripts_test.sh:14 — closeout_facts.py 복사

## 4. 현재 문제

`tests/closeout_packet_scripts_test.sh`는 closeout 패킷 검증용 스크립트를 복사해서 테스트 환경을 구성합니다. 그런데 실제 번들에는 `scripts/replay_closeout.sh`, `scripts/verify_closeout_packet.py`, `scripts/closeout_facts.py`가 없습니다. 이 상태는 단순한 테스트 누락이 아니라, 번들이 테스트 기준으로 자가완결적이지 않다는 뜻입니다.

이 문제를 방치하면 CI에서 실패하거나, 누군가 테스트를 임시로 제외하는 식으로 품질 기준이 흐려질 수 있습니다. closeout 기능이 아직 제품 범위에 있다면 누락 파일을 복구해야 하고, 기능이 제거되었다면 테스트도 함께 정리해야 합니다.

## 5. 수정 방향

권장 방향은 closeout 기능을 유지하는 것입니다. 이미 테스트가 존재하므로 기능 계약으로 보는 편이 안전합니다. 누락된 파일을 복구하고, 테스트 시작 시 필수 파일 존재 여부를 먼저 검증하도록 바꿉니다.

closeout 기능이 제거된 경우에는 테스트를 조건부 skip으로만 덮지 말고, 테스트 자체를 제거하거나 새로운 기능 범위에 맞게 재작성해야 합니다.

## 6. 구현 절차

- [ ] 필수 스크립트 존재 여부를 테스트 초반에 검사합니다.
- [ ] 누락된 스크립트가 실제로 필요한 기능이면 `scripts/` 아래에 복구합니다.
- [ ] 세 스크립트가 Git 추적 대상인지 확인합니다.
- [ ] 압축 번들 재생성 시 manifest에 세 파일이 들어가는지 확인합니다.
- [ ] closeout 기능이 제거된 경우 테스트 목적을 재정의하거나 제거합니다.

## 7. 코드 레벨 변경안

```bash
required_scripts=(
  "scripts/replay_closeout.sh"
  "scripts/verify_closeout_packet.py"
  "scripts/closeout_facts.py"
)

for file in "${required_scripts[@]}"; do
  if [[ ! -f "$repo_root/$file" ]]; then
    echo "missing required closeout script: $file" >&2
    exit 1
  fi
done
```

복구 후 기대 파일 구조입니다.

```text
scripts/
  replay_closeout.sh
  verify_closeout_packet.py
  closeout_facts.py
  zygisk_next_bootstrap.sh
  zygisk_next_watchdog.sh
  check-bridge-boundaries.sh
```

## 8. 테스트 계획

- [ ] `bash tests/closeout_packet_scripts_test.sh` 단독 실행
- [ ] 전체 shell test 묶음 실행
- [ ] 압축 번들 재생성 후 `BUNDLE_MANIFEST.txt`에 세 파일 포함 확인
- [ ] 실행 권한이 필요한 파일은 `chmod +x` 확인

## 9. 문서화 반영

테스트 문서에는 closeout 스크립트가 어떤 산출물을 검증하는지 적어야 합니다. 특정 릴리스 빌드에서만 closeout 패킷이 생성된다면, 테스트 실행 조건도 명확히 문서화합니다.

## 10. 완료 기준

- closeout 테스트가 파일 누락 없이 통과한다.
- `scripts/` 아래 closeout 관련 파일이 실제 번들에 포함된다.
- 테스트 실패 시 누락 파일명이 명확히 출력된다.

## 11. 주의할 리스크

- 파일만 추가하고 기능 동작을 검증하지 않으면 껍데기 테스트가 될 수 있습니다.
- 조건부 skip을 남용하면 CI에서 영구적으로 실행되지 않는 죽은 테스트가 될 수 있습니다.


---

# STEP 02. bridge optional/readiness 계약 정리

우선순위: **P0**

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


---

# STEP 03. 설정 변경 API 원자성 보장

우선순위: **P0**

## 1. 목적

설정 변경 API는 메모리 상태를 먼저 바꾸고 파일 저장을 나중에 합니다. 저장 실패 시 API는 실패했는데 런타임 상태는 변경되는 불일치가 생깁니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/http/ConfigRoutes.kt`
- `app/src/main/java/party/qwer/iris/ConfigManager.kt`
- `app/src/main/java/party/qwer/iris/config/ConfigPolicy.kt`
- `app/src/main/java/party/qwer/iris/ConfigStateStore.kt`

## 3. 확인된 위치

- ConfigRoutes.kt:40 — saveConfigNow()
- ConfigManager.kt:86 — saveConfigNow()
- ConfigManager.kt:211 — dbPollingRate setter
- ConfigPolicy.kt:132 — validate(state)

## 4. 현재 문제

`ConfigRoutes`는 `applyConfigUpdate()`를 먼저 호출한 뒤 `saveConfigNow()`를 호출합니다. `applyConfigUpdate()` 경로에서 setter가 즉시 `ConfigStateStore`의 `snapshotUser`와 `appliedUser`를 바꿀 수 있습니다. 그 뒤 파일 저장이 실패하면 HTTP 응답은 500이지만 메모리 상태는 이미 새 값이 됩니다.

운영자는 “설정 변경 실패”라고 받았는데 프로세스는 새 설정으로 움직이는 상태가 됩니다. 장애 분석에서 매우 위험합니다.

## 5. 수정 방향

설정 변경을 “후보 생성 → 검증 → 파일 저장 → 메모리 반영” 순서로 바꿔야 합니다. 현재 mutator가 `ConfigManager`를 직접 바꾸는 구조를 제거하고, `ConfigMutationPlan` 같은 변경 계획 객체를 만들도록 합니다.

임시 rollback 방식도 가능하지만, 변경 중 다른 스레드가 새 값을 읽을 수 있어 완전하지 않습니다. 권장 방향은 persist-then-commit 구조입니다.

## 6. 구현 절차

- [ ] `ConfigMutationPlan` 자료 구조를 추가합니다.
- [ ] `ConfigPolicy`는 `ConfigManager`를 직접 변경하지 않고 현재 `UserConfigState`와 요청을 받아 후보 상태를 만듭니다.
- [ ] 후보 상태를 기존 validation 규칙과 확장 validation 규칙으로 검증합니다.
- [ ] `ConfigManager.persistThenCommit(plan)`을 추가합니다.
- [ ] 파일 저장이 성공한 뒤에만 `ConfigStateStore.replace()`로 런타임 상태를 교체합니다.
- [ ] 파일 저장 실패 시 기존 state를 그대로 유지합니다.
- [ ] 즉시 적용 설정과 재시작 필요 설정은 `applyImmediately` 플래그로 구분합니다.

## 7. 코드 레벨 변경안

```kotlin
internal data class ConfigMutationPlan(
    val responseName: String,
    val candidateSnapshot: UserConfigState,
    val applyImmediately: Boolean,
    val requiresRestart: Boolean,
)
```

```kotlin
internal object ConfigPolicy {
    fun planUpdate(
        current: UserConfigState,
        name: String,
        request: ConfigRequest,
    ): ConfigMutationPlan {
        return when (name) {
            "dbrate" -> planDbRateUpdate(current, name, request)
            "sendrate" -> planSendRateUpdate(current, name, request)
            "botport" -> planBotPortUpdate(current, name, request)
            "endpoint" -> planEndpointUpdate(current, name, request)
            else -> throw ApiRequestException("unknown config name: $name")
        }
    }
}
```

```kotlin
internal fun persistThenCommit(plan: ConfigMutationPlan): Boolean {
    val current = stateStore.current()
    val candidateRuntime =
        current.copy(
            snapshotUser = plan.candidateSnapshot,
            appliedUser =
                if (plan.applyImmediately) {
                    plan.candidateSnapshot
                } else {
                    current.appliedUser
                },
            isDirty = true,
        )

    if (!persistence.save(candidateRuntime.snapshotUser)) {
        return false
    }

    stateStore.replace(candidateRuntime.copy(isDirty = false))
    return true
}
```

## 8. 테스트 계획

- [ ] persistence save 실패 fake를 주입해 runtime state 불변 확인
- [ ] `dbrate`처럼 즉시 적용 설정의 성공/실패 테스트
- [ ] `botport`처럼 재시작 필요 설정의 성공/실패 테스트
- [ ] 동시 설정 변경 요청에서 상태가 꼬이지 않는지 테스트
- [ ] 실패 응답 후 `/config` 조회 결과가 기존 값인지 확인

## 9. 문서화 반영

API 문서에 설정 변경 순서를 명시합니다. 특히 “저장 실패 시 effective runtime config는 기존 값으로 유지된다”는 계약을 써야 합니다. 응답의 `applied`, `requiresRestart`, `persisted` 의미도 함께 문서화합니다.

## 10. 완료 기준

- 설정 저장 실패 시 `snapshotUser`와 `appliedUser`가 변경되지 않는다.
- 성공한 설정만 런타임에 반영된다.
- API 응답과 실제 상태가 일치한다.

## 11. 주의할 리스크

- 기존 테스트가 setter side effect를 전제로 작성되어 있으면 수정이 필요합니다.
- ConfigPolicy가 커지므로 plan 생성과 validation 책임을 명확히 나눠야 합니다.


---

# STEP 04. config 파일 로드 validation 강화

우선순위: **P0**

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


---

# STEP 05. shell quoting 및 명령 주입 방어

우선순위: **P0**

## 1. 목적

`iris_control`, zygisk 스크립트, Rust daemon에서 경로와 token이 shell command에 안전하게 quote되지 않습니다. 특수문자 포함 값이 명령을 깨거나 주입으로 이어질 수 있습니다.

## 2. 대상 파일

- `iris_control`
- `scripts/zygisk_next_bootstrap.sh`
- `scripts/zygisk_next_watchdog.sh`
- `tools/iris-daemon/src/config_sync.rs`
- `tools/iris-daemon/src/launch_spec.rs`

## 3. 확인된 위치

- iris_control:133 — cat '$IRIS_CONFIG_PATH'
- iris_control:186 — build_remote_runtime_command()
- iris_control:204 — su root sh -c '$remote_command'
- zygisk_next_bootstrap.sh:72 — run_root_shell()
- zygisk_next_bootstrap.sh:140 — chmod 0777
- zygisk_next_watchdog.sh:5 — hardcoded bootstrap path
- config_sync.rs:93 — cat {}
- config_sync.rs:164 — build_device_sha256_command()

## 4. 현재 문제

스크립트는 환경변수와 경로를 shell command 문자열 안에 직접 삽입합니다. token, 경로, URL에 작은따옴표, 공백, `$()`, 백틱, 줄바꿈이 들어가면 명령이 깨지거나 의도치 않은 명령이 실행될 수 있습니다.

특히 token은 신뢰할 수 있는 값이라고 생각하기 쉽지만, 운영 환경에서는 secret store, CI 변수, 수동 입력 등을 통해 들어옵니다. shell 경계에서는 모든 값을 의심해야 합니다.

## 5. 수정 방향

모든 shell command 조립 지점에 안전한 quoting 함수를 적용합니다. 더 좋은 방향은 token을 명령줄에 직접 싣지 않고 권한 제한된 env 파일로 전달하는 것입니다.

Bash 쪽은 `sh_quote()`를 공통으로 두고, Rust 쪽은 이미 있는 `shell_quote()`를 모든 inner command의 변수에도 적용합니다. 바깥 shell만 quote하고 inner script에 raw path를 넣는 실수도 막아야 합니다.

## 6. 구현 절차

- [ ] `iris_control`에 `sh_quote()`와 `env_assign()`를 추가합니다.
- [ ] `build_remote_runtime_command()`에서 모든 env 값을 quote합니다.
- [ ] `su root sh -c '$remote_command'` 형태를 제거하고 `su root sh -c $(sh_quote "$remote_command")`로 바꿉니다.
- [ ] `get_iris_http_port()`의 config path도 quote합니다.
- [ ] `zygisk_next_bootstrap.sh`의 `run_root_shell()`에도 `sh_quote()`를 적용합니다.
- [ ] `zygisk_next_watchdog.sh`의 bootstrap 기본 경로를 script-relative 경로로 바꿉니다.
- [ ] `chmod 0777`이 정말 필요한지 검토하고 가능하면 `0700`으로 낮춥니다.
- [ ] Rust `config_sync.rs`에서 `cat`, `sha256sum`, `chmod` 모두 path quote를 적용합니다.

## 7. 코드 레벨 변경안

```bash
sh_quote() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

env_assign() {
  local name="$1"
  local value="$2"
  printf '%s=%s ' "$name" "$(sh_quote "$value")"
}
```

```bash
build_remote_runtime_command() {
  local redirect_suffix="${1:-}"

  {
    env_assign IRIS_CONFIG_PATH "$IRIS_CONFIG_PATH"
    env_assign IRIS_WEBHOOK_TOKEN "$IRIS_WEBHOOK_TOKEN"
    env_assign IRIS_BOT_TOKEN "$IRIS_BOT_TOKEN"
    env_assign IRIS_LOG_LEVEL "$IRIS_LOG_LEVEL"
    env_assign CLASSPATH "$IRIS_APK_PATH"
    printf 'exec app_process / party.qwer.iris.Main%s' "$redirect_suffix"
  }
}

remote_command="$(build_remote_runtime_command ' > /dev/null 2>&1 &')"
"${ADB_CMD[@]}" shell "su root sh -c $(sh_quote "$remote_command")"
```

```rust
let config_path = shell_quote(&cfg.init.config_dest);
let device_config = match adb.shell(&format!("cat {config_path}")).await {
    Ok(content) => content,
    Err(error) => {
        tracing::warn!(error = %error, "디바이스 config 읽기 실패 — config drift check 건너뜀");
        return Ok(false);
    }
};
```

## 8. 테스트 계획

- [ ] `IRIS_CONFIG_PATH="/data/local/tmp/iris config ' weird.json"`로 command builder 테스트
- [ ] `IRIS_WEBHOOK_TOKEN="abc' $(touch /tmp/pwned)"`로 명령 주입이 실행되지 않는지 테스트
- [ ] `IRIS_BOT_TOKEN`에 백틱과 공백을 넣어도 실행 명령이 깨지지 않는지 테스트
- [ ] Rust `config_sync` 단위 테스트에서 path에 공백/작은따옴표 포함
- [ ] 스크립트 로그에 token 원문이 출력되지 않는지 확인

## 9. 문서화 반영

운영 문서에 secret 전달 원칙을 적습니다. 명령줄 인자로 secret을 싣지 않는 것, env 파일 권한을 0600으로 두는 것, 로그에 secret 원문을 남기지 않는 것을 명시합니다.

## 10. 완료 기준

- 특수문자 포함 경로와 token으로도 명령이 깨지지 않는다.
- 추가 shell 명령이 실행되지 않는다.
- token이 로그나 process command line에 불필요하게 노출되지 않는다.

## 11. 주의할 리스크

- quote 함수를 부분적으로만 적용하면 안전하다는 착각이 생깁니다. 모든 shell 경계를 inventory로 관리해야 합니다.
- env 파일 방식으로 바꿀 경우 파일 삭제와 권한 관리까지 함께 구현해야 합니다.


---

# STEP 06. SSE 재연결 이벤트 유실 race 수정

우선순위: **P0**

## 1. 목적

현재 SSE 연결 재개 시 replay를 먼저 하고 subscriber를 나중에 엽니다. 그 사이 발생한 이벤트가 replay에도 live channel에도 들어가지 않는 유실 창이 있습니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/SseEventBus.kt`
- `app/src/main/java/party/qwer/iris/http/MemberRoutes.kt`

## 3. 확인된 위치

- SseEventBus.kt:190 — openSubscriberChannelSuspend()
- MemberRoutes.kt:76 — replayEnvelopesSuspend(lastEventId)
- MemberRoutes.kt:78 — openSubscriberChannelSuspend()

## 4. 현재 문제

MemberRoutes는 replay를 먼저 쓰고 그 뒤 live subscriber channel을 엽니다. replay와 subscribe 사이에 새 이벤트가 emit되면 해당 이벤트는 replay에도 없고 live channel에도 없습니다. Last-Event-ID 기반 재연결에서 가장 피해야 할 유실 창입니다.

## 5. 수정 방향

subscriber 등록과 replay 계산을 actor 내부 단일 command로 처리합니다. 외부 라우트가 replay 후 subscribe를 직접 조합하지 않게 하고, `openSubscriberWithReplaySuspend(afterId)` 하나로 처리합니다. 중복 가능성은 event id 기준으로 방어합니다.

## 6. 구현 절차

- [ ] `SseCommand.OpenSubscriberWithReplay` 추가
- [ ] `SubscriberReplay(replay, channel)` 자료 구조 추가
- [ ] actor 내부에서 subscriber 등록과 replay 계산을 같은 turn에서 처리
- [ ] MemberRoutes에서 기존 replay/open channel 조합 제거
- [ ] 라우트에서 `lastWrittenEventId` 기준 중복 방어
- [ ] persistent store와 memory buffer 양쪽 테스트

## 7. 코드 레벨 변경안

```kotlin
data class OpenSubscriberWithReplay(
    val afterId: Long,
    val reply: CompletableDeferred<SubscriberReplay>,
) : SseCommand

internal data class SubscriberReplay(
    val replay: List<SseEventEnvelope>,
    val channel: Channel<SseEventEnvelope>,
)
```

```kotlin
val opened = bus.openSubscriberWithReplaySuspend(lastEventId)
val channel = opened.channel
var lastWrittenEventId = lastEventId

opened.replay.forEach { envelope ->
    if (envelope.id > lastWrittenEventId) {
        writeStringUtf8(formatSseFrame(envelope))
        lastWrittenEventId = envelope.id
    }
}
flush()
```

## 8. 테스트 계획

- [ ] 재연결 중 event emit을 강제로 끼워 넣는 race 테스트
- [ ] replay/live 중복 id가 한 번만 write되는지 테스트
- [ ] replay window miss 처리 테스트
- [ ] persistent store replay 테스트

## 9. 문서화 반영

운영 문서에 SSE 재연결 중 이벤트 유실을 막기 위해 subscriber 등록과 replay 계산을 원자적으로 처리한다고 적습니다. 클라이언트는 id 기준 중복 제거를 해야 한다고 안내합니다.

## 10. 완료 기준

- replay와 subscribe 사이의 유실 창이 사라진다.
- 중복 이벤트는 id 기준으로 방어된다.
- Last-Event-ID 재연결 계약이 문서화된다.

## 11. 주의할 리스크

- store 기반 replay와 live channel 등록 순서 때문에 중복이 생길 수 있습니다.
- 중복 제거를 반드시 넣어야 합니다.


---

# STEP 07. SSE frame multiline 안정화

우선순위: **P0**

## 1. 목적

SSE payload에 줄바꿈이 들어가면 현재 frame 조립 방식이 표준 형식을 깨뜨릴 수 있습니다. 서버 formatter와 Rust client parser를 같이 고쳐야 합니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/http/SseEventEnvelope.kt`
- `app/src/main/java/party/qwer/iris/http/MemberRoutes.kt`
- `tools/iris-ctl/src/sse.rs`

## 3. 확인된 위치

- SseEventEnvelope.kt:10 — initialSseFrames()
- MemberRoutes.kt:92 — live SSE writeStringUtf8
- tools/iris-ctl/src/sse.rs:41 — parse_message()

## 4. 현재 문제

현재 서버는 `data: ${payload}` 형태로 payload를 한 줄에 그대로 붙입니다. payload에 줄바꿈이 포함되면 다음 줄은 `data:` prefix 없이 들어가거나 payload 내부 문자열이 SSE field처럼 해석될 수 있습니다. Rust client도 `data:` 줄을 하나만 저장합니다.

## 5. 수정 방향

서버에는 `formatSseFrame()` 공통 함수를 만들고, payload를 줄 단위로 쪼개 각 줄마다 `data: `를 붙입니다. Rust parser는 여러 data line을 모아 `\n`으로 합쳐 JSON parse합니다.

## 6. 구현 절차

- [ ] `formatSseFrame()` 공통 함수 추가
- [ ] payload 줄바꿈을 `
- [ ] `으로 정규화
- [ ] 각 payload line마다 `data: ` prefix 적용
- [ ] event type에서 줄바꿈 제거
- [ ] Rust parser에서 `Vec<&str>`로 여러 data line 수집

## 7. 코드 레벨 변경안

```kotlin
internal fun formatSseFrame(event: SseEventEnvelope): String =
    buildString {
        append("id: ").append(event.id).append('\n')
        append("event: ").append(sanitizeSseField(event.eventType)).append('\n')

        event.payload
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .forEach { line ->
                append("data: ").append(line).append('\n')
            }

        append('\n')
    }
```

```rust
let mut data_lines: Vec<&str> = Vec::new();

if let Some(data) = line.strip_prefix("data: ") {
    data_lines.push(data);
}

let data = data_lines.join("\n");
serde_json::from_str::<SseEvent>(&data).ok()
```

## 8. 테스트 계획

- [ ] multiline payload frame 테스트
- [ ] event type 줄바꿈 sanitize 테스트
- [ ] Rust parser multiple data line 테스트
- [ ] 빈 data line 테스트

## 9. 문서화 반영

SSE 문서에 multiline payload 처리 규칙을 적습니다. 클라이언트는 여러 data line을 `\n`으로 연결해야 합니다.

## 10. 완료 기준

- 서버의 모든 SSE write 경로가 공통 formatter를 사용한다.
- payload 줄바꿈이 frame 구조를 깨지 않는다.
- Rust client가 여러 data line을 정상 복원한다.

## 11. 주의할 리스크

- server/client를 같이 고치지 않으면 호환성 문제가 생깁니다.
- JSON payload는 보통 한 줄이라 문제가 숨어 있을 수 있으니 테스트를 강제해야 합니다.


---

# STEP 08. SseEventBus actor 예외 안정성 강화

우선순위: **P0**

## 1. 목적

SSE actor 내부에서 store 예외가 발생하면 command reply가 완료되지 않아 호출자가 무한 대기할 수 있습니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/SseEventBus.kt`

## 3. 확인된 위치

- SseEventBus.kt:260 — runActor()
- SseEventBus.kt:391 — dispatchSuspend()

## 4. 현재 문제

`SseEventBus` actor는 command를 순차 처리하지만 command 처리 중 예외가 발생했을 때 reply를 완료하는 공통 방어가 부족합니다. store insert/replay/maxId 예외가 actor를 죽이면 호출자는 `reply.await()`에서 끝나지 않을 수 있습니다.

## 5. 수정 방향

actor loop는 command별 try/catch를 적용하고, 실패한 command에 대해서도 반드시 reply를 complete 또는 completeExceptionally 처리해야 합니다. store 계층 오류는 로그로 남기고 command별 안전 fallback을 반환합니다.

## 6. 구현 절차

- [ ] `handleCommand(command)`로 command 처리 분리
- [ ] `runActor()` for-loop에서 try/catch 적용
- [ ] `failCommand(command, error)` 구현
- [ ] `dispatchSuspend()`에서 `trySend()`와 actor closed fallback 적용
- [ ] store maxId 초기화 실패 시 0 fallback과 로그 기록

## 7. 코드 레벨 변경안

```kotlin
for (command in commands) {
    try {
        handleCommand(command)
    } catch (error: Throwable) {
        IrisLogger.error("[SseEventBus] actor command failed: ${error.message}", error)
        failCommand(command, error)
    }
}
```

```kotlin
private fun failCommand(command: SseCommand, error: Throwable) {
    when (command) {
        is SseCommand.Emit -> command.reply.completeExceptionally(error)
        is SseCommand.Replay -> command.reply.complete(emptyList())
        is SseCommand.SubscriberCount -> command.reply.complete(0)
        is SseCommand.Close -> command.reply.complete(Unit)
        else -> Unit
    }
}
```

## 8. 테스트 계획

- [ ] fake store insert 예외 테스트
- [ ] fake store replay 예외 테스트
- [ ] actor close 이후 fallback 테스트
- [ ] maxId 초기화 실패 테스트

## 9. 문서화 반영

개발 문서에 actor command 추가 시 reply 완료 정책을 반드시 추가해야 한다고 적습니다.

## 10. 완료 기준

- store 예외 발생 시 호출자가 무한 대기하지 않는다.
- actor command 추가 시 실패 처리 정책이 명시된다.

## 11. 주의할 리스크

- fallback을 너무 관대하게 두면 실제 데이터 손실을 숨길 수 있습니다.
- emit 실패처럼 중요한 경로는 예외를 반환해야 합니다.


---

# STEP 09. Webhook outbox dispatcher 생명주기 동시성 수정

우선순위: **P1**

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


---

# STEP 10. Webhook outbox claim 안정화

우선순위: **P1**

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


---

# STEP 11. SQLite statement close 누락 수정

우선순위: **P1**

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


---

# STEP 12. 종료 순서와 checkpoint flush 수정

우선순위: **P1**

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


---

# STEP 13. ReplyAdmissionService actor 소유 상태 경계 수정

우선순위: **P1**

## 1. 목적

`ReplyAdmissionService`는 actor가 소유해야 할 mutable state를 shutdown 경로에서 actor 밖에서 직접 수정합니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/reply/ReplyAdmissionService.kt`

## 3. 확인된 위치

- ReplyAdmissionService.kt:120 — workerRegistry
- ReplyAdmissionService.kt:178 — shutdownSuspend()
- ReplyAdmissionService.kt:191 — closingWorkers.clear()

## 4. 현재 문제

`workerRegistry`, `closingWorkers`, `lifecycle`은 actor가 관리해야 하는 상태입니다. shutdown 경로에서 actor 밖에서 `closingWorkers.clear()`를 호출하면 actor 모델의 경계를 깹니다.

## 5. 수정 방향

actor 소유 상태는 actor command 안에서만 변경합니다. shutdown 후 정리도 `ShutdownCompleted` command를 통해 actor가 처리하도록 바꿉니다.

## 6. 구현 절차

- [ ] AdmissionCommand.ShutdownCompleted 추가
- [ ] handleShutdownCompleted에서 closingWorkers.clear 수행
- [ ] shutdownSuspend에서 worker close 후 command dispatch
- [ ] actor closed fallback 정책 정리

## 7. 코드 레벨 변경안

```kotlin
data class ShutdownCompleted(
    val reply: CompletableDeferred<Unit>,
) : AdmissionCommand

private fun handleShutdownCompleted(command: AdmissionCommand.ShutdownCompleted) {
    closingWorkers.clear()
    command.reply.complete(Unit)
}
```

## 8. 테스트 계획

- [ ] shutdown 중 worker closed event 테스트
- [ ] actor closed fallback 테스트
- [ ] debug snapshot actor state 테스트

## 9. 문서화 반영

개발 문서에 actor-owned mutable state는 actor command 밖에서 직접 수정하지 않는다고 적습니다.

## 10. 완료 기준

- actor-owned mutable state가 actor 밖에서 직접 변경되지 않는다.
- shutdown 경로에서도 상태 변경 순서가 예측 가능하다.

## 11. 주의할 리스크

- command 추가 시 handler 누락을 막기 위해 sealed command exhaustiveness를 활용합니다.


---

# STEP 14. multipart 입력 처리 강화

우선순위: **P1**

## 1. 목적

multipart reply collector가 알 수 없는 part를 조용히 무시합니다. 보안 경계에서는 모르는 입력을 명확히 거부해야 합니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/http/MultipartReplyCollector.kt`
- `app/src/main/java/party/qwer/iris/http/ReplyRoutes.kt`

## 3. 확인된 위치

- MultipartReplyCollector.kt:95 — acceptMetadata()
- MultipartReplyCollector.kt:118 — acceptImage()

## 4. 현재 문제

metadata가 아닌 form item과 image가 아닌 file item이 조용히 무시됩니다. 클라이언트 실수나 공격성 입력이 발견되지 않습니다.

## 5. 수정 방향

허용되는 part 이름을 `metadata`, `image`로 제한하고 나머지는 400으로 거부합니다. metadata 크기 제한은 image 크기 제한과 분리합니다.

## 6. 구현 절차

- [ ] unknown form/file part 거부
- [ ] metadata 중복 정책 확정
- [ ] image 중복 정책 확정
- [ ] formFieldLimit metadata 기준 적용 가능성 확인

## 7. 코드 레벨 변경안

```kotlin
when (part) {
    is PartData.FormItem -> {
        if (part.name != "metadata") invalidRequest("unsupported multipart form part: ${part.name}")
        acceptMetadata(part, multipart)
    }
    is PartData.FileItem -> {
        if (part.name != "image") invalidRequest("unsupported multipart file part: ${part.name}")
        acceptImage(part)
    }
    else -> invalidRequest("unsupported multipart part")
}
```

## 8. 테스트 계획

- [ ] unknown form part 400 테스트
- [ ] unknown file part 400 테스트
- [ ] metadata 초과 413 테스트
- [ ] 중복 metadata 정책 테스트

## 9. 문서화 반영

API 문서에 허용 multipart 필드, 크기 제한, 중복 필드 정책을 적습니다.

## 10. 완료 기준

- 모르는 multipart field가 조용히 무시되지 않는다.
- metadata와 image 크기 제한이 분리된다.

## 11. 주의할 리스크

- Ktor formFieldLimit가 file part에도 영향을 주는지 확인해야 합니다.


---

# STEP 15. 요청 body size 계산 overflow 방어

우선순위: **P1**

## 1. 목적

body byte count가 Int이고 `current + partBytes` 직접 덧셈을 사용합니다. 대용량/악성 입력에 대비해 Long과 overflow-safe check를 적용해야 합니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/http/RequestBodyReader.kt`
- `app/src/main/java/party/qwer/iris/http/ReplyRoutes.kt`

## 3. 확인된 위치

- RequestBodyReader.kt:45 — var totalRead = 0
- ReplyRoutes.kt:169 — accumulateReplyBodyBytes()

## 4. 현재 문제

`totalRead`가 Int이고 body size 덧셈이 overflow-safe 하지 않습니다. low-level reader는 나중에 대용량 경로에 재사용될 수 있습니다.

## 5. 수정 방향

크기 계산은 Long으로 통일합니다. 덧셈 전 `partBytes > maxBytes - current` 방식으로 초과 여부를 검사합니다.

## 6. 구현 절차

- [ ] totalRead Long 변경
- [ ] declaredContentLength < 0 검증
- [ ] 덧셈 전 overflow-safe check 적용
- [ ] streaming digest와 input stream 경로 모두 수정

## 7. 코드 레벨 변경안

```kotlin
if (partBytes < 0) requestRejected("invalid part size", HttpStatusCode.BadRequest)
if (current > maxBytes || partBytes > maxBytes - current) {
    requestRejected("request body too large", HttpStatusCode.PayloadTooLarge)
}
return current + partBytes
```

## 8. 테스트 계획

- [ ] max size 직전 성공 테스트
- [ ] max size 직후 413 테스트
- [ ] 음수 content length 400 테스트
- [ ] overflow성 입력 테스트

## 9. 문서화 반영

API 문서에 body size limit과 초과 응답 코드를 적습니다.

## 10. 완료 기준

- body size 계산에서 integer overflow가 발생하지 않는다.
- 초과 요청은 항상 413으로 거부된다.

## 11. 주의할 리스크

- 타입 변경으로 호출부 signature가 바뀌면 fixture 수정이 필요합니다.


---

# STEP 16. 인증 nonce replay/DoS 경계 강화

우선순위: **P1**

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


---

# STEP 17. webhook endpoint default/route 정책 정리

우선순위: **P1**

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


---

# STEP 18. Rust/Kotlin canonical query 계약 일치화

우선순위: **P1**

## 1. 목적

Kotlin 서버는 query key/value를 URL encode하지만 Rust client는 정렬만 하고 encoding을 하지 않습니다. 특수문자 query가 추가되면 서명 불일치가 납니다.

## 2. 대상 파일

- `tools/iris-common/src/auth.rs`
- `app/src/main/java/party/qwer/iris/http/RequestBodyReader.kt`
- `docs/auth-contract.md`

## 3. 확인된 위치

- tools/iris-common/src/auth.rs:11 — canonical_target()

## 4. 현재 문제

서명 canonical target은 서버와 client가 완전히 같아야 합니다. Rust client는 현재 key/value를 그대로 붙이므로 한글, 공백, `&`, `=`, `%`가 들어가면 서버와 다른 문자열에 서명합니다.

## 5. 수정 방향

Kotlin과 Rust가 같은 golden fixture를 읽어 같은 결과를 내도록 계약 테스트를 만듭니다. Rust 쪽에는 percent encoding을 적용합니다.

## 6. 구현 절차

- [ ] canonical query 규칙 문서화
- [ ] Rust canonical_target key/value encode
- [ ] Kotlin/Rust 공통 fixture 추가
- [ ] 특수문자 query test 추가
- [ ] 빈 value 정책 확인

## 7. 코드 레벨 변경안

```rust
fn encode_query_component(value: &str) -> String {
    utf8_percent_encode(value, NON_ALPHANUMERIC).to_string()
}
```

## 8. 테스트 계획

- [ ] Rust canonical target fixture test
- [ ] Kotlin canonical target fixture test
- [ ] 서명 end-to-end 테스트
- [ ] query 순서 변경 테스트

## 9. 문서화 반영

`docs/auth-contract.md`를 추가해 서명 문자열 구성 방식을 문서화합니다.

## 10. 완료 기준

- 동일 입력에 대해 Kotlin과 Rust가 같은 canonical target을 만든다.
- 특수문자 query에서도 서명 검증이 성공한다.

## 11. 주의할 리스크

- percent encoding 방식이 Kotlin과 완전히 같은지 fixture로 확인해야 합니다.


---

# STEP 19. APK checksum 검증 SHA-256 전환

우선순위: **P2**

## 1. 목적

`iris_control`이 MD5 checksum을 사용합니다. 우발적 손상 검사용으로도 SHA-256으로 올리는 편이 맞습니다.

## 2. 대상 파일

- `iris_control`
- `README.md`
- `릴리스 산출물 생성 스크립트`

## 3. 확인된 위치

- iris_control:274 — verify_md5_if_available()

## 4. 현재 문제

MD5는 충돌 공격에 취약하고 릴리스 산출물 검증 용도로 부적절합니다. APK와 `.MD5`를 같은 위치에서 받으면 공급망 공격을 완전히 막지는 못하지만, 최소한 SHA-256으로 올려야 합니다.

## 5. 수정 방향

`.MD5` 대신 `.SHA256` 파일을 사용하고 checksum 형식이 64자리 hex인지 검증합니다. 보안 요구가 높으면 APK signing certificate pinning이나 서명 파일 검증을 추가합니다.

## 6. 구현 절차

- [ ] IRIS_SHA256_URL 추가
- [ ] verify_sha256_if_available 구현
- [ ] MD5 함수 제거 또는 deprecated
- [ ] 릴리스 산출물에 .SHA256 추가

## 7. 코드 레벨 변경안

```bash
if ! [[ "$downloaded_sha256" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo "Invalid SHA256 checksum format."
  return 1
fi
```

## 8. 테스트 계획

- [ ] 정상 SHA-256 검증 테스트
- [ ] checksum mismatch 실패 테스트
- [ ] checksum 형식 오류 테스트
- [ ] checksum 파일 없음 정책 테스트

## 9. 문서화 반영

README에서 MD5 표현을 제거하고 SHA-256 checksum 검증 방식을 설명합니다.

## 10. 완료 기준

- APK 다운로드 검증이 SHA-256 기반으로 동작한다.
- 릴리스 산출물에 SHA-256 checksum이 포함된다.

## 11. 주의할 리스크

- SHA-256도 같은 채널에서 받으면 공급망 공격을 완전히 막지는 못합니다.


---

# STEP 20. readiness error 정보 노출 최소화

우선순위: **P2**

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


---

# STEP 21. ConfigStateStore lock 구조 단순화

우선순위: **P2**

## 1. 목적

`ConfigStateStore.updateUserState()`와 `mutate()`가 중첩 synchronized 구조입니다. 현재 deadlock은 아니지만 lock 경계를 단순화하는 편이 좋습니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/ConfigStateStore.kt`

## 3. 확인된 위치

- ConfigStateStore.kt — mutate()/updateUserState()

## 4. 현재 문제

reentrant lock이라 당장 deadlock은 아니지만, transform 안에서 외부 callback이 호출되면 lock 경계가 위험해질 수 있습니다.

## 5. 수정 방향

`mutate()` 하나만 lock을 잡게 하거나 `updateUserState()`가 직접 state를 바꾸는 방식으로 단순화합니다. 원자성 수정과 함께 `replace()`를 추가합니다.

## 6. 구현 절차

- [ ] updateUserState의 @Synchronized 제거
- [ ] mutate 내부에서만 state 교체
- [ ] replace(next) 추가
- [ ] transform side effect 점검

## 7. 코드 레벨 변경안

```kotlin
fun updateUserState(...): ConfigRuntimeState =
    mutate { current ->
        // compute next state only
    }
```

## 8. 테스트 계획

- [ ] 동시 update stress 테스트
- [ ] replace current 테스트
- [ ] 동일 값 update dirty 테스트

## 9. 문서화 반영

개발 문서에 state store는 단일 lock 경계를 유지한다고 적습니다.

## 10. 완료 기준

- ConfigStateStore의 lock 경계가 단순하고 예측 가능하다.

## 11. 주의할 리스크

- 원자성 PR과 함께 넣는 것이 좋습니다.


---

# STEP 22. SqliteSseEventStore prune 입력 검증

우선순위: **P2**

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


---

# README / 운영 문서 수정안

이 문서는 코드 수정과 함께 반드시 반영해야 하는 문서 문구를 모은 것입니다. 코드만 고치고 문서가 기존 계약을 계속 말하면 운영자가 잘못된 설정을 하게 됩니다.

## 1. Bridge 모듈 설명 수정안

### 권장 문구

```md
## Bridge 모듈

Iris의 bridge 모듈은 이미지 전송, 마크다운 전송 등 확장 메시지 전송 기능에 사용됩니다.

텍스트 웹훅만 사용하는 경우 bridge 없이도 Iris를 실행할 수 있습니다. 이 경우 `IRIS_REQUIRE_BRIDGE`를 설정하지 않거나 `false`로 둡니다.

```bash
IRIS_REQUIRE_BRIDGE=false
```

bridge 기능을 반드시 사용해야 하는 환경에서는 다음 값을 설정합니다.

```bash
IRIS_REQUIRE_BRIDGE=true
IRIS_BRIDGE_TOKEN=...
```

`IRIS_REQUIRE_BRIDGE=true`일 때는 bridge token이 없거나 bridge health check가 실패하면 `/ready`가 `503 Service Unavailable`을 반환합니다.

`IRIS_REQUIRE_BRIDGE=false`일 때는 bridge가 설치되어 있지 않아도 텍스트 웹훅 기능이 정상 설정되어 있으면 `/ready`가 성공할 수 있습니다.
```

### 기존 문장 교체 방향

수정 전:

```md
Bridge 모듈은 이미지 전송용이며 선택 사항입니다.
```

수정 후:

```md
Bridge 모듈은 이미지/확장 메시지 전송에 필요합니다. 텍스트 웹훅만 사용하는 환경에서는 선택 사항이며, `IRIS_REQUIRE_BRIDGE` 설정에 따라 readiness 검사에 포함됩니다.
```

## 2. Health check 문서 수정안

```md
## Health check

Iris는 다음 health endpoint를 제공합니다.

### `GET /health`

프로세스가 실행 중인지 확인하는 기본 health endpoint입니다. 설정이 완전히 끝나지 않았더라도 프로세스가 응답 가능하면 성공할 수 있습니다.

### `GET /ready`

Iris가 실제 요청을 처리할 준비가 되었는지 확인하는 readiness endpoint입니다.

`/ready`는 다음 조건을 확인합니다.

- inbound signing secret 설정 여부
- outbound webhook token 설정 여부
- bot control token 설정 여부
- webhook endpoint 설정 여부
- `IRIS_REQUIRE_BRIDGE=true`인 경우 bridge token 및 bridge health 상태

운영 환경에서는 `/ready`가 실패하더라도 HTTP 응답에는 자세한 내부 설정 정보가 노출되지 않을 수 있습니다. 자세한 실패 사유는 서버 로그에서 확인합니다.
```

외부 bind 경고도 함께 추가합니다.

```md
주의: `IRIS_BIND_HOST=0.0.0.0`으로 설정하면 health endpoint가 외부 네트워크에서 접근 가능할 수 있습니다. 운영 환경에서는 방화벽, 프록시 접근 제어 또는 내부 네트워크 바인딩을 사용하세요.
```

## 3. 환경변수 표 수정안

```md
## 환경변수

| 이름 | 기본값 | 설명 |
|---|---:|---|
| `IRIS_CONFIG_PATH` | `/data/local/tmp/iris_config.json` | Iris 설정 파일 경로 |
| `IRIS_BIND_HOST` | `127.0.0.1` | HTTP 서버 bind host |
| `IRIS_REQUIRE_BRIDGE` | `false` | bridge를 readiness 필수 조건으로 볼지 여부 |
| `IRIS_BRIDGE_TOKEN` | 없음 | bridge 호출에 사용하는 token |
| `IRIS_WEBHOOK_TOKEN` | 없음 | outbound webhook 인증 token |
| `IRIS_BOT_TOKEN` | 없음 | bot control API 인증 token |
| `IRIS_READY_VERBOSE` | `false` | `/ready` 실패 사유를 HTTP 응답에 자세히 노출할지 여부. 개발용으로만 권장 |
| `IRIS_ALLOW_CLEARTEXT_HTTP` | `false` | cleartext HTTP webhook 허용 여부 |
| `IRIS_WEBHOOK_TRANSPORT_SECURITY_MODE` | `strict` | webhook transport 보안 정책 |
```

`IRIS_READY_VERBOSE`는 실제 코드에 추가하는 경우에만 문서화합니다.

## 4. Webhook endpoint 정책 문서화

route별 endpoint를 허용하는 방향이라면 아래 문구를 사용합니다.

```md
## Webhook endpoint 설정

Iris는 기본 webhook endpoint와 route별 webhook endpoint를 지원합니다.

기본 endpoint는 route별 endpoint가 없을 때 fallback으로 사용됩니다.

```json
{
  "endpoint": "https://example.com/iris/default",
  "webhooks": {
    "default": "https://example.com/iris/default",
    "image": "https://example.com/iris/image",
    "command": "https://example.com/iris/command"
  }
}
```

특정 route에 endpoint가 설정되어 있지 않고 기본 endpoint도 없으면, 해당 route의 webhook delivery는 전송되지 않고 reject 처리됩니다.

route별 endpoint만 사용하는 구성도 가능합니다. 이 경우 해당 route에 대해서만 delivery가 전송됩니다.
```

default endpoint를 필수로 유지하는 방향이라면 아래 문구를 사용합니다.

```md
## Webhook endpoint 설정

Iris는 기본 webhook endpoint를 필수 설정으로 사용합니다.

route별 endpoint는 특정 route의 전송 대상을 덮어쓰기 위해 사용합니다. route별 endpoint가 없는 경우 기본 endpoint로 전송됩니다.

기본 endpoint가 없으면 Iris는 webhook dispatcher를 ready 상태로 보지 않습니다.
```

두 문구를 동시에 넣으면 안 됩니다. 코드 정책과 같은 방향 하나만 선택해야 합니다.

## 5. 설정 변경 API 원자성 문서화

```md
## 설정 변경 API의 적용 방식

Iris의 설정 변경 API는 다음 순서로 동작합니다.

1. 요청 값 검증
2. 변경 후보 설정 생성
3. 설정 파일 저장
4. 런타임 설정 반영

설정 파일 저장에 실패하면 런타임 설정은 변경되지 않습니다. 즉, API가 실패 응답을 반환한 경우 현재 실행 중인 Iris의 effective config는 기존 값으로 유지됩니다.

일부 설정은 즉시 적용되며, 일부 설정은 재시작 후 적용됩니다. API 응답의 `applied`와 `requiresRestart` 값을 통해 적용 방식을 확인할 수 있습니다.
```

응답 예시:

```json
{
  "name": "dbrate",
  "persisted": true,
  "applied": true,
  "requiresRestart": false
}
```

```json
{
  "name": "botport",
  "persisted": true,
  "applied": false,
  "requiresRestart": true
}
```

## 6. 설정 파일 validation 정책 문서화

```md
## 설정 파일 검증

Iris는 설정 파일을 로드할 때 다음 항목을 검증합니다.

- HTTP port 범위
- DB polling rate 범위
- message send rate 범위
- jitter 범위
- webhook endpoint URL 형식
- webhook route 이름 형식
- command route prefix 형식
- image message route 형식
- secret/token 값의 앞뒤 공백 및 제어문자 포함 여부

잘못된 설정 파일이 감지되면 Iris는 해당 설정을 적용하지 않고 오류를 기록합니다.

route 이름은 영문자, 숫자, `-`, `_`만 사용할 수 있습니다.
```

```md
운영 환경에서는 HTTPS webhook endpoint 사용을 권장합니다. cleartext HTTP endpoint를 사용하려면 명시적으로 `IRIS_ALLOW_CLEARTEXT_HTTP=true`를 설정해야 합니다.
```

## 7. Secret 전달 방식 운영 문서

```md
## Secret 전달 방식

Iris 실행 스크립트는 token과 secret 값을 shell command 문자열에 직접 삽입하지 않습니다.

원격 디바이스에서 실행할 때는 다음 원칙을 지킵니다.

- 모든 경로는 shell-safe quoting을 적용합니다.
- 모든 token/secret 값은 shell-safe quoting을 적용합니다.
- 가능하면 token/secret은 명령줄 인자가 아니라 권한이 제한된 env 파일로 전달합니다.
- env 파일은 `0600` 권한으로 생성합니다.
- 로그에는 token/secret 원문을 출력하지 않습니다.

경로 또는 token 값에 공백, 작은따옴표, 달러 기호, 백틱이 포함되어도 추가 shell 명령이 실행되어서는 안 됩니다.
```

## 8. SSE event delivery 문서

```md
## SSE event delivery

Iris의 SSE endpoint는 `Last-Event-ID` 기반 재연결을 지원합니다.

클라이언트가 연결을 끊은 뒤 재연결할 때 `Last-Event-ID`를 전달하면, Iris는 해당 id 이후의 이벤트를 replay합니다.

서버는 subscriber 등록과 replay 계산을 하나의 원자적 단계로 처리하여 재연결 중 발생한 이벤트가 유실되지 않도록 보장합니다.

클라이언트는 동일한 event id를 중복 수신할 가능성에 대비해 id 기준 중복 제거를 수행해야 합니다.
```

```md
SSE payload가 여러 줄인 경우 서버는 각 줄마다 `data:` prefix를 붙여 전송합니다. 클라이언트는 여러 `data:` line을 `\n`으로 연결해 하나의 payload로 복원해야 합니다.
```

## 9. Shutdown behavior 문서

```md
## Shutdown behavior

Iris는 종료 시 다음 순서로 자원을 정리합니다.

1. ingress service 종료
2. dispatch loop 종료 대기
3. webhook outbox dispatcher 종료
4. reply admission worker 종료
5. config 저장
6. checkpoint journal flush
7. database close

종료 과정에서 이미 처리 완료된 command checkpoint는 flush되어야 합니다. 강제 종료나 프로세스 kill이 발생하면 마지막 checkpoint가 저장되지 않을 수 있으므로, 운영 환경에서는 graceful shutdown을 사용하세요.
```

## 10. Auth signing contract 문서

```md
# Iris request signing contract

Iris의 인증 서명은 다음 값을 기준으로 생성합니다.

```text
METHOD
CANONICAL_TARGET
TIMESTAMP
NONCE
BODY_SHA256_HEX
```

`CANONICAL_TARGET`은 path와 정규화된 query string으로 구성됩니다.

query parameter 정규화 규칙:

1. key와 value를 URL parameter encoding한다.
2. encoded key 기준 오름차순 정렬한다.
3. key가 같으면 encoded value 기준 오름차순 정렬한다.
4. `key=value` 형식으로 연결한다.
5. 여러 parameter는 `&`로 연결한다.
```

예시:

```text
path: /rooms/1/stats
query:
  period = 최근 7일
  filter = a&b=c

canonical target:
/rooms/1/stats?filter=a%26b%3Dc&period=%EC%B5%9C%EA%B7%BC%207%EC%9D%BC
```

---

# PR 분리 계획

전체 수정은 한 PR로 넣으면 리뷰가 거의 불가능합니다. 아래 순서대로 나누면 기능 계약, 보안 경계, 런타임 안정성, 입력 검증을 단계적으로 안정화할 수 있습니다.

## PR 1 — 테스트 번들 무결성 복구

포함 항목:

- closeout 테스트 누락 파일 수정
- 테스트 시작부 필수 파일 검증 추가
- `BUNDLE_MANIFEST.txt` 정합성 확인

완료 조건:

- 모든 shell test가 누락 파일 없이 실행된다.
- closeout 기능이 유지되는지 제거되는지 명확히 결정되어 있다.

## PR 2 — bridge optional/readiness 계약 정리

포함 항목:

- `RuntimeConfigReadiness.bridgeRequired` 추가
- `/ready` bridge 검사 조건화
- `IRIS_REQUIRE_BRIDGE` 문서화
- readiness 테스트 추가

완료 조건:

- 텍스트 전용 모드와 bridge 필수 모드가 테스트로 구분된다.
- README와 코드가 같은 계약을 말한다.

## PR 3 — 설정 변경 원자성

포함 항목:

- `ConfigMutationPlan` 도입
- persist-then-commit 구조 적용
- `ConfigStateStore.replace()` 추가
- 저장 실패 테스트 추가

완료 조건:

- 설정 저장 실패 시 runtime state가 변경되지 않는다.

## PR 4 — shell quoting hardening

포함 항목:

- `iris_control` `sh_quote()` 적용
- zygisk script quoting 적용
- Rust `config_sync` path quoting 적용
- env file 기반 secret 전달 검토
- secret 전달 운영 문서 추가

완료 조건:

- 특수문자 포함 경로/token으로도 명령 주입이 발생하지 않는다.

## PR 5 — SSE 안정성

포함 항목:

- `openSubscriberWithReplaySuspend()` 추가
- SSE frame formatter 추가
- Rust SSE parser multiline 처리
- actor 예외 fallback 처리
- SSE reconnect 테스트 추가

완료 조건:

- 재연결 중 이벤트 유실이 없고 multiline payload가 정상 처리된다.

## PR 6 — shutdown / actor boundary / SQLite 안정성

포함 항목:

- `CommandIngressService.closeSuspend()` 추가
- `ObserverHelper.close()` 순서 수정
- `ReplyAdmissionService` actor state boundary 수정
- `AndroidSqliteDriver` statement close
- `SqliteWebhookDeliveryStore` claim 구조 수정

완료 조건:

- 종료 시 checkpoint 유실 위험이 줄고 SQLite resource leak 가능성이 제거된다.

## PR 7 — 입력 검증 및 인증 계약 강화

포함 항목:

- multipart unknown part 거부
- body size overflow 방어
- config load validation 확장
- Kotlin/Rust canonical query fixture 추가
- nonce 정책 문서화

완료 조건:

- 비정상 입력이 조용히 통과하지 않고 명확히 reject된다.

## PR 8 — P2 운영 품질 개선

포함 항목:

- APK checksum SHA-256 전환
- readiness error 정보 노출 최소화
- ConfigStateStore lock 단순화
- SqliteSseEventStore prune 검증
- 관련 README 업데이트

완료 조건:

- 릴리스/운영/보안 품질 항목이 문서와 테스트로 고정된다.

---

# 최종 릴리스 전 점검표

아래 항목은 모든 PR이 합쳐진 뒤 릴리스 직전에 확인합니다.

## 1. 자동 테스트

- [ ] shell tests 전체 통과
- [ ] Gradle unit test 전체 통과
- [ ] Rust workspace test 전체 통과
- [ ] Kotlin/Rust canonical auth fixture test 통과
- [ ] closeout 테스트가 누락 파일 없이 통과

## 2. readiness / bridge

- [ ] bridge optional 모드에서 `/ready` 성공
- [ ] bridge required 모드에서 bridge token 없음 시 `/ready` 실패
- [ ] bridge required 모드에서 bridge health 실패 시 `/ready` 실패
- [ ] README의 bridge 설명과 실제 동작 일치

## 3. config

- [ ] 설정 저장 실패 시 runtime state 불변
- [ ] 즉시 적용 설정과 재시작 필요 설정 응답 구분
- [ ] config 파일 직접 수정 시 잘못된 endpoint 거부
- [ ] config 파일 직접 수정 시 잘못된 route 거부
- [ ] secret/token 앞뒤 공백 또는 제어문자 거부

## 4. shell / secret

- [ ] token에 작은따옴표, 공백, `$()`, 백틱 포함 시 command injection 없음
- [ ] config path에 공백/작은따옴표 포함 시 command 동작
- [ ] env 파일 사용 시 권한 0600
- [ ] 로그에 token 원문 미노출

## 5. SSE

- [ ] Last-Event-ID 재연결 중 이벤트 유실 없음
- [ ] replay/live 중복 이벤트 id 기준 방어
- [ ] multiline payload frame 정상
- [ ] Rust client가 multiple data line 복원
- [ ] SSE actor store 예외 시 무한 대기 없음

## 6. webhook outbox

- [ ] dispatcher start 중복 호출에도 polling loop 하나
- [ ] close 중 start 경합에서 상태 꼬임 없음
- [ ] 동시 claim에서 동일 delivery 중복 claim 없음
- [ ] claim update 실패 row가 결과에서 제외됨
- [ ] route별 endpoint 정책이 README와 일치

## 7. shutdown

- [ ] graceful shutdown 시 dispatch loop 종료 대기
- [ ] 종료 직전 checkpoint flush
- [ ] reply admission worker shutdown 경계 테스트 통과
- [ ] database close 전 pending flush 완료

## 8. 입력 검증

- [ ] multipart unknown form part 400
- [ ] multipart unknown file part 400
- [ ] metadata size 초과 413
- [ ] body size overflow-safe check
- [ ] max body 초과 요청 413

## 9. 릴리스 산출물

- [ ] APK SHA-256 checksum 생성
- [ ] checksum mismatch 실패 확인
- [ ] README의 MD5 표현 제거
- [ ] bundle manifest에 테스트가 참조하는 파일 모두 포함

## 10. 운영 보안

- [ ] `/ready` 상세 reason 기본 비노출
- [ ] `IRIS_READY_VERBOSE`는 개발용으로만 문서화
- [ ] `IRIS_BIND_HOST=0.0.0.0` 사용 시 접근 제어 안내

---

# 테스트 체크리스트

## 1. Shell tests

```bash
bash tests/iris_control_env_loading_test.sh
bash tests/iris_control_device_selection_test.sh
bash tests/iris_control_stop_race_test.sh
bash tests/zygisk_next_bootstrap_test.sh
bash tests/zygisk_next_bootstrap_bridge_ready_test.sh
bash tests/zygisk_next_watchdog_test.sh
bash tests/closeout_packet_scripts_test.sh
```

## 2. Android/Kotlin tests

```bash
./gradlew test
```

환경에 따라 Gradle wrapper가 외부 네트워크에서 distribution을 내려받으려 할 수 있습니다. CI에서는 wrapper distribution cache 또는 내부 mirror를 준비합니다.

## 3. Rust tests

```bash
cargo test --workspace
```

## 4. 신규로 추가해야 하는 핵심 테스트

### 설정 원자성

- [ ] persistence save 실패 시 runtime config 불변
- [ ] immediate apply 설정 성공/실패
- [ ] restart required 설정 성공/실패
- [ ] 동시 설정 변경 요청

### bridge readiness

- [ ] `IRIS_REQUIRE_BRIDGE=false`에서 bridge token 없음 + `/ready` 성공
- [ ] `IRIS_REQUIRE_BRIDGE=true`에서 bridge token 없음 + `/ready` 실패
- [ ] bridge health fail 시 required 모드에서 `/ready` 실패

### shell quoting

- [ ] config path에 공백 포함
- [ ] config path에 작은따옴표 포함
- [ ] token에 `$()` 포함
- [ ] token에 백틱 포함
- [ ] token 원문 로그 미노출

### SSE

- [ ] reconnect 중 emit된 이벤트 유실 없음
- [ ] replay/live 중복 id 제거
- [ ] multiline payload formatter
- [ ] Rust parser multiple data line
- [ ] store insert/replay 예외 fallback

### webhook outbox

- [ ] dispatcher start 동시 호출
- [ ] stale claim candidate 제외
- [ ] 동시 claim 중복 방지
- [ ] route별 endpoint dispatch
- [ ] endpoint 누락 시 entry reject

### shutdown

- [ ] close 반환 후 dispatch job 종료
- [ ] shutdown 직전 checkpoint flush
- [ ] reply admission shutdown 중 worker closed event

### multipart/body

- [ ] unknown multipart part 거부
- [ ] metadata 초과 거부
- [ ] image 초과 거부
- [ ] body size overflow 방어
- [ ] negative content length 거부

### auth contract

- [ ] Kotlin/Rust canonical target fixture 일치
- [ ] 한글 query encoding
- [ ] 공백 query encoding
- [ ] `&`, `=`, `%` query encoding
- [ ] nonce replay 동시 요청
