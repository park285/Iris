# STEP 03. 설정 변경 API 원자성 보장

우선순위: **P0**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 구현 반영 완료, Kotlin/Gradle 검증 보류
- 구현 근거: `app/src/main/java/party/qwer/iris/ConfigManager.kt`, `app/src/main/java/party/qwer/iris/ConfigUpdateOutcome.kt`, `app/src/main/java/party/qwer/iris/config/ConfigStateStore.kt`, `app/src/main/java/party/qwer/iris/config/ConfigPolicy.kt`, `app/src/main/java/party/qwer/iris/http/ConfigRoutes.kt`
- 검증 근거: `app/src/test/java/party/qwer/iris/ConfigManagerPersistenceTest.kt`, `app/src/test/java/party/qwer/iris/ConfigUpdateOutcomeTest.kt`, `app/src/test/java/party/qwer/iris/http/ConfigRoutesTest.kt`
- 메모: 현재 구현은 `ConfigMutationPlan`과 persist-then-commit 경계를 사용하며, save 실패 시 hot-applied 상태와 pending-restart 상태를 모두 유지하는 방향으로 정리되었습니다.

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
