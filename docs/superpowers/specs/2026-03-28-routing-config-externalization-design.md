# Routing Config Externalization

> Status: approved, pending implementation
> Date: 2026-03-28

## Problem

`WebhookRouter.kt`에 커맨드 prefix→route 매핑과 이미지 메시지 타입→route 매핑이 하드코딩되어 있다.
Config 인터페이스(`commandRoutePrefixes`, `imageMessageTypeRoutes`)는 이미 존재하지만,
config가 비어있으면 하드코딩 테이블로 폴백하는 하이브리드 구조.

개인 전용 프로젝트이므로 폴백 불필요 — 하드코딩 완전 제거, config-only 구동으로 전환한다.

## Current Hardcoded Values

### Command Route Prefixes (`commandRouteRules`)

```
settlement  → ["!정산", "!정산완료"]
chatbotgo   → ["!질문", "!이미지", "!그림", "!리셋", "!관리자", "!한강"]
```

### Image Message Type Routes (`defaultImageRouteRules`)

```
chatbotgo   → ["2"]
```

### Constants

| Constant | File | Action |
|---|---|---|
| `ROUTE_CHATBOTGO = "chatbotgo"` | WebhookRouter.kt:9 | Delete |
| `ROUTE_SETTLEMENT = "settlement"` | WebhookRouter.kt:10 | Delete |
| `IMAGE_MESSAGE_TYPE = "2"` | WebhookRouter.kt:75 | Delete (dead code) |
| `commandRouteRules` (private val) | WebhookRouter.kt:17-39 | Delete |
| `defaultImageRouteRules` (private val) | WebhookRouter.kt:41-44 | Delete |

## Design

### 1. WebhookRouter.kt — Remove All Hardcoded Fallbacks

**Before:**
```kotlin
val effectiveRules = config
    ?.commandRoutePrefixes()
    ?.takeIf { it.isNotEmpty() }
    ?.map { ... }
    ?: commandRouteRules          // ← hardcoded fallback
```

**After:**
```kotlin
val configRules = config
    ?.commandRoutePrefixes()
    ?.takeIf { it.isNotEmpty() }
    ?: return ROUTE_DEFAULT       // config 비어있으면 default route
```

동일하게 `resolveImageRoute`에서도 `defaultImageRouteRules` 폴백 제거.

### 2. ConfigValues / UserConfigValues — Default Seed Values

`emptyMap()` 기본값을 실제 운영값으로 변경:

```kotlin
@Serializable
data class UserConfigValues(
    // ...existing fields...
    val commandRoutePrefixes: Map<String, List<String>> = mapOf(
        "settlement" to listOf("!정산", "!정산완료"),
        "chatbotgo" to listOf("!질문", "!이미지", "!그림", "!리셋", "!관리자", "!한강"),
    ),
    val imageMessageTypeRoutes: Map<String, List<String>> = mapOf(
        "chatbotgo" to listOf("2"),
    ),
)
```

이렇게 하면:
- 기존 config.json에 해당 필드가 없는 경우 → deserialization 시 기본값이 적용됨
- 새 config.json 최초 생성 시 → 기본값으로 저장됨
- config API로 언제든 런타임 변경 가능

`ConfigValues`도 동일하게 기본값 변경.

### 3. Tests — Config Injection

- `H2cDispatcherRouteSupportTest`: 하드코딩 route 이름 비교 → config를 주입하고 그 config의 route 이름을 검증하는 방식
- WebhookRouter 단위 테스트: config 있을 때 매칭, config 비어있을 때 default route, 알 수 없는 커맨드일 때 default route

### 4. Runtime Config (Device)

현재 운영 `/data/local/tmp/config.json`에 이미 다른 설정이 있으므로,
다음 중 하나로 seed:

- **(권장)** 코드 배포 후 앱 재시작 → `UserConfigValues` 기본값이 config.json에 자동 병합 (kotlinx.serialization의 default 값 동작)
- 또는 config API `PATCH /config` 로 수동 설정

별도의 마이그레이션 코드는 불필요 — kotlinx.serialization이 missing field에 default value를 채운다.

## Scope

### In Scope
- `WebhookRouter.kt` 하드코딩 제거
- `ConfigValues.kt`, `UserConfigValues.kt` 기본값 변경
- `ConfigLayers.kt` 기본값 전파 확인
- 관련 테스트 수정 (H2cDispatcherRouteSupportTest, 기타 라우팅 테스트)

### Out of Scope
- `DEFAULT_WEBHOOK_ROUTE = "default"` 상수 — 매칭 실패 시 fallback route 이름으로 유지
- messageId 포맷 (`"kakao-log-${sourceLogId}-$route"`) — 이번 범위 밖
- H2cDispatcher/WebhookOutbox 라우팅 로직 이중 구현 통합 — 별도 작업

## Files to Modify

| File | Change |
|---|---|
| `delivery/webhook/WebhookRouter.kt` | Remove constants, fallback tables, dead code |
| `model/ConfigValues.kt` | Default seed values for routing maps |
| `model/UserConfigValues.kt` | Default seed values for routing maps |
| `ConfigLayers.kt` | Verify defaults propagate (likely no change needed) |
| `test/.../H2cDispatcherRouteSupportTest.kt` | Config-injected assertions |
| `test/.../delivery/webhook/` routing tests | Update for no-fallback behavior |
