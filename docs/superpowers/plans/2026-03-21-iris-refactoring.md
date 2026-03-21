# Iris 코드베이스 리팩토링 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** God Object 분해, 동시성 버그 수정, 성능 최적화, 테스트 기반 확보를 통해 Iris 코드베이스의 유지보수성과 안정성을 개선한다.

**Architecture:** Risk-Ordered 4단계 접근. Phase 1(동시성 버그)→2(God Object 분해)→3(성능)→4(DI+테스트). 각 Phase와 Task는 독립 커밋 가능하며, 매 커밋 시 `./gradlew lint ktlintCheck assembleDebug test` 통과 필수.

**Tech Stack:** Kotlin 21, Android SDK 35 (app_process), OkHttp, Ktor/Netty, kotlinx.serialization, JUnit

**Spec:** `docs/superpowers/specs/2026-03-21-iris-refactoring-design.md`

---

## Phase 1: 동시성·안전성 버그 수정

---

### Task 1: KakaoDecrypt.keyCache 경쟁 조건 수정

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/KakaoDecrypt.kt:321`
- Test: `app/src/test/java/party/qwer/iris/KakaoDecryptTest.kt` (기존 테스트 통과 확인)

- [ ] **Step 1: keyCache.getOrPut → computeIfAbsent 교체**

`KakaoDecrypt.kt:321`에서:

```kotlin
// Before
val key = keyCache.getOrPut(saltStr) { deriveKey(KEY_BYTES, salt, 2, 32) }

// After
val key = keyCache.computeIfAbsent(saltStr) { deriveKey(KEY_BYTES, salt, 2, 32) }
```

`keyCache` 타입도 `ConcurrentHashMap`으로 명시 변경:
```kotlin
// Before (line 17)
private val keyCache: MutableMap<String, ByteArray?> = java.util.concurrent.ConcurrentHashMap()

// After
private val keyCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
```

`computeIfAbsent`는 nullable value를 허용하지 않으므로 `ByteArray?` → `ByteArray`로 변경. `deriveKey`는 non-null을 반환하므로 안전.

- [ ] **Step 2: 기존 테스트 통과 확인**

Run: `./gradlew test --tests "party.qwer.iris.KakaoDecryptTest" -q`
Expected: PASS

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/KakaoDecrypt.kt
git commit -m "fix: KakaoDecrypt keyCache 경쟁 조건 수정 (getOrPut → computeIfAbsent)"
```

---

### Task 2: @Synchronized + runBlocking 데드락 위험 제거

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/DBObserver.kt:43-52`
- Modify: `app/src/main/java/party/qwer/iris/KakaoProfileIndexer.kt:62-68`
- Modify: `app/src/main/java/party/qwer/iris/Replier.kt:72-84,89-100`

- [ ] **Step 1: DBObserver.stopPolling 수정**

`DBObserver.kt:43-52` — `@Synchronized` 내에서 job capture만 하고 `runBlocking`은 밖으로:

```kotlin
fun stopPolling() {
    val job = synchronized(this) {
        val captured = pollingJob ?: return
        pollingJob = null
        captured
    }

    runBlocking {
        job.cancelAndJoin()
    }
    IrisLogger.info("DB Polling thread stopped.")
}
```

`@Synchronized` 어노테이션 제거하고 `synchronized(this)` 블록 사용.

- [ ] **Step 2: KakaoProfileIndexer.stop 수정**

`KakaoProfileIndexer.kt:62-68` — 동일 패턴:

```kotlin
fun stop() {
    val job = synchronized(this) {
        val captured = workerJob ?: return
        workerJob = null
        captured
    }

    runBlocking {
        captured.cancelAndJoin()
    }
}
```

`@Synchronized` 어노테이션 제거.

- [ ] **Step 3: Replier.restartMessageSender 수정**

`Replier.kt:72-84` — job cancel을 synchronized 밖에서:

```kotlin
@OptIn(DelicateCoroutinesApi::class)
fun restartMessageSender() {
    val jobToCancel = synchronized(this@Companion) {
        if (messageChannel.isClosedForSend) {
            IrisLogger.error("[Replier] Cannot restart message sender after shutdown")
            return
        }
        val captured = messageSenderJob
        messageSenderJob = null
        captured
    }

    jobToCancel?.let { runBlocking { it.cancelAndJoin() } }
    startMessageSender()
}
```

- [ ] **Step 4: Replier.shutdown 수정**

`Replier.kt:89-100` — channel close는 synchronized 안에서, join은 밖에서:

```kotlin
fun shutdown() {
    IrisLogger.info("[Replier] Shutting down message channel...")
    val jobToJoin = synchronized(this@Companion) {
        messageChannel.close()
        val captured = messageSenderJob
        messageSenderJob = null
        captured
    }

    jobToJoin?.let { runBlocking { it.join() } }
    IrisLogger.info("[Replier] Message channel closed")
}
```

- [ ] **Step 5: 전체 빌드 확인**

Run: `./gradlew lint ktlintCheck assembleDebug test -q`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/DBObserver.kt \
       app/src/main/java/party/qwer/iris/KakaoProfileIndexer.kt \
       app/src/main/java/party/qwer/iris/Replier.kt
git commit -m "fix: @Synchronized + runBlocking 데드락 위험 제거 (4개 메서드)"
```

---

### Task 3: ObserverHelper.advanceLastLogId 경로 통일

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/ObserverHelper.kt:96-108`
- Test: `app/src/test/java/party/qwer/iris/ObserverHelperLogicTest.kt` (기존 테스트 통과 확인)

- [ ] **Step 1: parseMetadata 내 lastLogId 직접 변경 제거**

`ObserverHelper.kt:96-108`에서 `lastLogId = logEntry.id` 직접 할당을 `advanceLastLogId()` 호출로 변경:

```kotlin
// Before
private fun parseMetadata(logEntry: KakaoDB.ChatLogEntry): ParsedLogMetadata? =
    runCatching {
        JSONObject(logEntry.metadata)
    }.map { metadata ->
        ParsedLogMetadata(
            enc = metadata.optInt("enc", 0),
            origin = metadata.optString("origin"),
        )
    }.getOrElse { error ->
        IrisLogger.error("[ObserverHelper] Invalid metadata for logId=${logEntry.id}: ${error.message}")
        lastLogId = logEntry.id
        null
    }

// After
private fun parseMetadata(logEntry: KakaoDB.ChatLogEntry): ParsedLogMetadata? =
    runCatching {
        JSONObject(logEntry.metadata)
    }.map { metadata ->
        ParsedLogMetadata(
            enc = metadata.optInt("enc", 0),
            origin = metadata.optString("origin"),
        )
    }.getOrElse { error ->
        IrisLogger.error("[ObserverHelper] Invalid metadata for logId=${logEntry.id}: ${error.message}")
        advanceLastLogId(logEntry.id)
        null
    }
```

- [ ] **Step 2: 기존 테스트 통과 확인**

Run: `./gradlew test --tests "party.qwer.iris.ObserverHelperLogicTest" -q`
Expected: PASS

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/ObserverHelper.kt
git commit -m "fix: ObserverHelper parseMetadata 실패 시 advanceLastLogId 경로 통일"
```

---

## Phase 2: God Object 분해

---

### Task 4: IrisServer — QuerySanitizer 추출

**Files:**
- Create: `app/src/main/java/party/qwer/iris/QuerySanitizer.kt`
- Modify: `app/src/main/java/party/qwer/iris/IrisServer.kt:470-523`
- Test: `app/src/test/java/party/qwer/iris/IrisServerQueryValidationTest.kt` (기존 테스트가 QuerySanitizer 참조하도록)

- [ ] **Step 1: QuerySanitizer.kt 생성**

`IrisServer.kt`에서 다음을 추출:
- `SAFE_PRAGMAS` (line 470-486)
- `WRITE_KEYWORD_PATTERN` (line 487-491)
- `isReadOnlyQuery()` (line 493-511)
- `requireQueryText()` (line 513-517)
- `requireReadOnlyQuery()` (line 519-523)

```kotlin
package party.qwer.iris

internal val SAFE_PRAGMAS = setOf(
    "table_info", "table_xinfo", "index_list", "index_info",
    "foreign_key_list", "compile_options", "database_list",
    "collation_list", "encoding", "page_size", "page_count",
    "max_page_count", "freelist_count",
)

private val WRITE_KEYWORD_PATTERN = Regex(
    """\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|ATTACH|DETACH|REINDEX|VACUUM)\b""",
    RegexOption.IGNORE_CASE,
)

internal fun isReadOnlyQuery(query: String): Boolean {
    val normalized = query.trimStart()
    if (normalized.isBlank()) return false
    val upper = normalized.uppercase()

    if (upper.startsWith("PRAGMA")) {
        val pragmaBody = normalized.substringAfter("PRAGMA", "").trimStart()
        val pragmaName = pragmaBody.split('(', '=', ' ', ';').first().trim().lowercase()
        return pragmaName in SAFE_PRAGMAS
    }

    if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) return false
    return !WRITE_KEYWORD_PATTERN.containsMatchIn(normalized)
}
```

- [ ] **Step 2: IrisServer.kt에서 해당 코드 삭제**

`IrisServer.kt`의 line 470-523 (SAFE_PRAGMAS, WRITE_KEYWORD_PATTERN, isReadOnlyQuery, requireQueryText, requireReadOnlyQuery)를 삭제. `requireQueryText`와 `requireReadOnlyQuery`는 IrisServer 내부 private으로 유지하되 `isReadOnlyQuery`를 `QuerySanitizer`에서 import.

- [ ] **Step 3: 기존 테스트 통과 확인**

Run: `./gradlew test --tests "party.qwer.iris.IrisServerQueryValidationTest" -q`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/QuerySanitizer.kt \
       app/src/main/java/party/qwer/iris/IrisServer.kt
git commit -m "refactor: QuerySanitizer 추출 (IrisServer 분해 1/3)"
```

---

### Task 5: IrisServer — ConfigUpdateHandler 추출

**Files:**
- Create: `app/src/main/java/party/qwer/iris/ConfigUpdateHandler.kt`
- Modify: `app/src/main/java/party/qwer/iris/IrisServer.kt:215-299`
- Test: `app/src/test/java/party/qwer/iris/WebhookConfigUpdateTest.kt` (기존 테스트 통과 확인)

- [ ] **Step 1: ConfigUpdateHandler.kt 생성**

`IrisServer.kt`에서 다음을 추출:
- `applyConfigUpdate()` (line 215-225)
- `updateEndpointConfig()` (line 227-248)
- `updateDbRateConfig()` (line 250-265)
- `updateSendRateConfig()` (line 267-281)
- `updateBotPortConfig()` (line 284-299)
- `ConfigUpdateOutcome` data class (line 415-419)
- `resolveEndpointRoute()` (line 441-450)

```kotlin
package party.qwer.iris

import party.qwer.iris.model.ConfigRequest

internal data class ConfigUpdateOutcome(
    val name: String,
    val applied: Boolean,
    val requiresRestart: Boolean,
)

internal fun applyConfigUpdate(name: String, request: ConfigRequest): ConfigUpdateOutcome =
    when (name) {
        "endpoint" -> updateEndpointConfig(name, request)
        "dbrate" -> updateDbRateConfig(name, request)
        "sendrate" -> updateSendRateConfig(name, request)
        "botport" -> updateBotPortConfig(name, request)
        else -> throw ApiRequestException("unknown config '$name'")
    }

// ... (각 update 함수와 resolveEndpointRoute를 그대로 이동)
```

`ApiRequestException`의 가시성을 `internal`로 변경 (현재 private class).

- [ ] **Step 2: IrisServer.kt에서 해당 코드 삭제, import 추가**

`IrisServer.configureConfigRoutes()`에서 `applyConfigUpdate(name, request)` 호출을 유지하되, 함수가 top-level로 이동했으므로 import만 추가.

- [ ] **Step 3: 빌드 및 테스트 확인**

Run: `./gradlew test --tests "party.qwer.iris.WebhookConfigUpdateTest" -q`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/ConfigUpdateHandler.kt \
       app/src/main/java/party/qwer/iris/IrisServer.kt
git commit -m "refactor: ConfigUpdateHandler 추출 (IrisServer 분해 2/3)"
```

---

### Task 6: IrisServer — ReplyAdmission 확장

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/ReplyAdmission.kt:1-27`
- Modify: `app/src/main/java/party/qwer/iris/IrisServer.kt:301-359`
- Test: `app/src/test/java/party/qwer/iris/ReplyAdmissionTest.kt` (기존 테스트 통과 확인)

- [ ] **Step 1: IrisServer에서 reply admission 로직 추출**

`IrisServer.kt`의 `enqueueReply()` (line 301-330) 내 검증 로직과 `admitReply()` (line 332-359)를 `ReplyAdmission.kt`로 이동.

`ReplyAdmission.kt`에 추가:
```kotlin
internal fun admitReply(
    replyRequest: ReplyRequest,
    roomId: Long,
    notificationReferer: String,
    threadId: Long?,
    threadScope: Int?,
): ReplyAdmissionResult =
    when (replyRequest.type) {
        ReplyType.TEXT -> Replier.sendMessage(notificationReferer, roomId, extractTextPayload(replyRequest), threadId, threadScope)
        ReplyType.IMAGE -> Replier.sendPhoto(roomId, extractSingleImagePayload(replyRequest))
        ReplyType.IMAGE_MULTIPLE -> Replier.sendMultiplePhotos(roomId, extractImagePayloads(replyRequest))
    }
```

payload 추출 함수(`extractTextPayload`, `extractSingleImagePayload`, `extractImagePayloads`)도 함께 이동.

- [ ] **Step 2: IrisServer.enqueueReply에서 admitReply 호출**

`IrisServer.kt`의 `enqueueReply()`를 단순화:
```kotlin
private fun enqueueReply(replyRequest: ReplyRequest): ReplyAcceptedResponse {
    val roomId = replyRequest.room.toLongOrNull() ?: invalidRequest("room must be a numeric string")
    val threadId = replyRequest.threadId?.let {
        it.toLongOrNull() ?: invalidRequest("threadId must be a numeric string")
    }
    val threadScope = try {
        validateReplyThreadScope(replyRequest.type, threadId, replyRequest.threadScope)
    } catch (e: IllegalArgumentException) {
        invalidRequest(e.message ?: "invalid threadScope")
    }
    if (threadId != null && !supportsThreadReply(replyRequest.type)) {
        invalidRequest("threadId is only supported for text replies")
    }

    val admission = admitReply(replyRequest, roomId, notificationReferer, threadId, threadScope)
    // ... (나머지 동일)
}
```

- [ ] **Step 3: 빌드 및 테스트 확인**

Run: `./gradlew test --tests "party.qwer.iris.ReplyAdmissionTest" -q`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/ReplyAdmission.kt \
       app/src/main/java/party/qwer/iris/IrisServer.kt
git commit -m "refactor: ReplyAdmission 확장 (IrisServer 분해 3/3)"
```

---

### Task 7: H2cDispatcher — WebhookPayloadBuilder 추출

**Files:**
- Create: `app/src/main/java/party/qwer/iris/bridge/WebhookPayloadBuilder.kt`
- Modify: `app/src/main/java/party/qwer/iris/bridge/H2cDispatcher.kt:405-445`
- Test: `app/src/test/java/party/qwer/iris/bridge/H2cDispatcherThreadPayloadTest.kt` (기존 테스트 통과 확인)

- [ ] **Step 1: WebhookPayloadBuilder.kt 생성**

`H2cDispatcher.kt`의 `buildQueuedDelivery()` (line 405-445) 내 JSON 직렬화를 추출:

```kotlin
package party.qwer.iris.bridge

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun buildWebhookPayload(command: RoutingCommand, route: String, messageId: String): String =
    buildJsonObject {
        put("route", route)
        put("messageId", messageId)
        put("sourceLogId", command.sourceLogId)
        put("text", command.text)
        put("room", command.room)
        put("sender", command.sender)
        put("userId", command.userId)
        if (!command.chatLogId.isNullOrBlank()) put("chatLogId", command.chatLogId)
        if (!command.roomType.isNullOrBlank()) put("roomType", command.roomType)
        if (!command.roomLinkId.isNullOrBlank()) put("roomLinkId", command.roomLinkId)
        if (!command.threadId.isNullOrBlank()) put("threadId", command.threadId)
        command.threadScope?.let { put("threadScope", it) }
        if (!command.messageType.isNullOrBlank()) put("type", command.messageType)
        if (!command.attachment.isNullOrBlank()) put("attachment", command.attachment)
    }.toString()
```

- [ ] **Step 2: H2cDispatcher.buildQueuedDelivery 단순화**

```kotlin
private fun buildQueuedDelivery(command: RoutingCommand, webhookUrl: String, route: String): QueuedDelivery {
    val messageId = "kakao-log-${command.sourceLogId}-$route"
    return QueuedDelivery(
        url = webhookUrl,
        messageId = messageId,
        route = route,
        payloadJson = buildWebhookPayload(command, route, messageId),
    )
}
```

`kotlinx.serialization.json.buildJsonObject`와 `put` import를 `H2cDispatcher.kt`에서 제거.

- [ ] **Step 3: 기존 테스트 통과 확인**

Run: `./gradlew test --tests "party.qwer.iris.bridge.H2cDispatcherThreadPayloadTest" -q`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/bridge/WebhookPayloadBuilder.kt \
       app/src/main/java/party/qwer/iris/bridge/H2cDispatcher.kt
git commit -m "refactor: WebhookPayloadBuilder 추출 (H2cDispatcher 분해 1/2)"
```

---

### Task 8: H2cDispatcher — WebhookHttpClientFactory 추출

**Files:**
- Create: `app/src/main/java/party/qwer/iris/bridge/WebhookHttpClientFactory.kt`
- Modify: `app/src/main/java/party/qwer/iris/bridge/H2cDispatcher.kt:50-106`
- Test: `app/src/test/java/party/qwer/iris/bridge/H2cDispatcherClientConfigTest.kt` (기존 테스트 통과 확인)

- [ ] **Step 1: WebhookHttpClientFactory.kt 생성**

`H2cDispatcher.kt`에서 추출:
- `WebhookTransport` enum (line 453-456)
- `createBaseClient()` (line 85-94)
- h2c/http1 클라이언트 생성 로직 (line 78-79)
- `clientFor()` (line 96-106)

```kotlin
package party.qwer.iris.bridge

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

internal enum class WebhookTransport { H2C, HTTP1 }

internal fun resolveWebhookTransport(transportOverride: String?): WebhookTransport {
    val raw = transportOverride?.trim()?.lowercase()
        ?: System.getenv("IRIS_WEBHOOK_TRANSPORT")?.trim()?.lowercase().orEmpty()
    return when (raw) {
        "http1", "http1_1", "http", "https" -> WebhookTransport.HTTP1
        else -> WebhookTransport.H2C
    }
}

internal class WebhookHttpClientFactory(
    private val transport: WebhookTransport,
    sharedDispatcher: Dispatcher,
    sharedConnectionPool: ConnectionPool,
) {
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .dispatcher(sharedDispatcher)
        .connectionPool(sharedConnectionPool)
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val h2cClient: OkHttpClient = baseClient.newBuilder()
        .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build()
    private val http1Client: OkHttpClient = baseClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1)).build()

    fun clientFor(webhookUrl: String): OkHttpClient {
        if (webhookUrl.startsWith("https://")) return http1Client
        return when (transport) {
            WebhookTransport.H2C -> h2cClient
            WebhookTransport.HTTP1 -> http1Client
        }
    }

    companion object {
        internal const val CONNECT_TIMEOUT_MS = 10_000L
        internal const val REQUEST_TIMEOUT_MS = 30_000L
        internal const val SOCKET_TIMEOUT_MS = 30_000L
    }
}
```

- [ ] **Step 2: H2cDispatcher에서 클라이언트 코드 제거, factory 사용**

`H2cDispatcher`의 프로퍼티를 다음으로 교체:
```kotlin
private val webhookTransport = resolveWebhookTransport(transportOverride)
private val sharedDispatcher = Dispatcher().apply {
    maxRequests = MAX_CONCURRENT_REQUESTS
    maxRequestsPerHost = MAX_CONCURRENT_REQUESTS_PER_HOST
}
private val sharedConnectionPool = ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_DURATION_MS, TimeUnit.MILLISECONDS)
private val clientFactory = WebhookHttpClientFactory(webhookTransport, sharedDispatcher, sharedConnectionPool)
```

`clientFor()` 호출을 `clientFactory.clientFor()`로 변경. `createBaseClient()`, `WebhookTransport` enum, timeout 상수를 `H2cDispatcher`에서 삭제.

- [ ] **Step 3: 기존 테스트 통과 확인**

Run: `./gradlew test --tests "party.qwer.iris.bridge.H2cDispatcherClientConfigTest" -q`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/bridge/WebhookHttpClientFactory.kt \
       app/src/main/java/party/qwer/iris/bridge/H2cDispatcher.kt
git commit -m "refactor: WebhookHttpClientFactory 추출 (H2cDispatcher 분해 2/2)"
```

---

### Task 9: KakaoDB — ChatLogDecryptor 추출

**Files:**
- Create: `app/src/main/java/party/qwer/iris/ChatLogDecryptor.kt`
- Modify: `app/src/main/java/party/qwer/iris/KakaoDB.kt:380-481`
- Modify: `app/src/main/java/party/qwer/iris/IrisServer.kt:404` (`KakaoDB.decryptRow` → `decryptRow` import)

- [ ] **Step 1: ChatLogDecryptor.kt 생성**

`KakaoDB.kt` companion object에서 추출:
- `decryptRow()` (line 412-423)
- `decryptMessageFields()` (line 425-448)
- `decryptProfileFields()` (line 450-462)
- `decryptRowValues()` (line 464-481)
- `MESSAGE_DECRYPT_KEYS` (line 385)
- `PROFILE_DECRYPT_KEYWORDS` / `PROFILE_DECRYPT_KEYS` (line 386-410)

```kotlin
package party.qwer.iris

import org.json.JSONException
import org.json.JSONObject

// KakaoDB.decryptRow 호환: 기존 호출부(IrisServer)에서 import 변경만 필요
fun decryptRow(inputRow: Map<String, String?>): Map<String, String?> {
    // ... (기존 코드 그대로 이동)
}

private val MESSAGE_DECRYPT_KEYS = arrayOf("message", "attachment")
// ... (나머지 상수와 함수)
```

- [ ] **Step 2: KakaoDB.kt companion에서 해당 코드 삭제**

`KakaoDB.companion`에서 `decryptRow`, `decryptMessageFields`, `decryptProfileFields`, `decryptRowValues`, `MESSAGE_DECRYPT_KEYS`, `PROFILE_DECRYPT_KEYWORDS`, `PROFILE_DECRYPT_KEYS` 삭제.

- [ ] **Step 3: IrisServer.kt 호출부 변경**

`IrisServer.kt:404`의 `KakaoDB.decryptRow(it)` → `decryptRow(it)` (top-level 함수 import)

- [ ] **Step 4: 빌드 및 테스트 확인**

Run: `./gradlew lint ktlintCheck assembleDebug test -q`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/ChatLogDecryptor.kt \
       app/src/main/java/party/qwer/iris/KakaoDB.kt \
       app/src/main/java/party/qwer/iris/IrisServer.kt
git commit -m "refactor: ChatLogDecryptor 추출 (KakaoDB 분해 1/2)"
```

---

### Task 10: KakaoDB — BotIdentityDetector 추출

**Files:**
- Create: `app/src/main/java/party/qwer/iris/BotIdentityDetector.kt`
- Modify: `app/src/main/java/party/qwer/iris/KakaoDB.kt:46-102`
- Test: `app/src/test/java/party/qwer/iris/KakaoDBBotUserIdResolutionTest.kt` (기존 테스트 통과 확인)

- [ ] **Step 1: BotIdentityDetector.kt 생성**

`KakaoDB.kt`에서 추출:
- `detectBotUserId()` (line 46)
- `detectBotUserIdByStringMatch()` (line 48-68)
- `detectBotUserIdByJsonFallback()` (line 70-102)
- `BOT_USER_ID_FALLBACK_SCAN_LIMIT` (line 384)
- `BotUserIdResolution` sealed interface (line 487-501)
- `resolveBotUserId()` (line 503-511)

`BotIdentityDetector`는 `SQLiteDatabase`를 받아 작동하는 top-level 함수로 구성:

```kotlin
package party.qwer.iris

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

internal fun detectBotUserId(db: SQLiteDatabase): Long =
    detectBotUserIdByStringMatch(db).takeIf { it > 0L } ?: detectBotUserIdByJsonFallback(db)

private fun detectBotUserIdByStringMatch(db: SQLiteDatabase): Long { /* 기존 코드, withPrimaryConnection 제거하고 db 직접 사용 */ }
private fun detectBotUserIdByJsonFallback(db: SQLiteDatabase): Long { /* 동일 */ }

// resolveBotUserId, BotUserIdResolution은 그대로 이동
```

- [ ] **Step 2: KakaoDB.init에서 호출부 변경**

```kotlin
// Before
when (val resolution = resolveBotUserId(detectBotUserId(), Configurable.botId)) {
// After
when (val resolution = resolveBotUserId(detectBotUserId(connection), Configurable.botId)) {
```

`KakaoDB`에서 `detectBotUserId*` 3개 메서드, `BOT_USER_ID_FALLBACK_SCAN_LIMIT`, `BotUserIdResolution`, `resolveBotUserId` 삭제.

- [ ] **Step 3: 기존 테스트 통과 확인**

Run: `./gradlew test --tests "party.qwer.iris.KakaoDBBotUserIdResolutionTest" -q`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/BotIdentityDetector.kt \
       app/src/main/java/party/qwer/iris/KakaoDB.kt
git commit -m "refactor: BotIdentityDetector 추출 (KakaoDB 분해 2/2)"
```

---

### Task 11: Replier — ImageEncoder 추출

**Files:**
- Create: `app/src/main/java/party/qwer/iris/ImageEncoder.kt`
- Modify: `app/src/main/java/party/qwer/iris/Replier.kt:388-455`
- Test: `app/src/test/java/party/qwer/iris/ReplierImageDetectionTest.kt` (기존 테스트 통과 확인)

- [ ] **Step 1: ImageEncoder.kt 생성**

`Replier.kt`에서 추출 (file-level functions, line 388-455):

```kotlin
package party.qwer.iris

import java.io.File
import java.util.Base64
import java.util.UUID

data class DecodedImage(val bytes: ByteArray, val extension: String)

private val base64MimeDecoder = Base64.getMimeDecoder()
internal const val MAX_IMAGE_PAYLOAD_BYTES = 20 * 1024 * 1024
internal const val MAX_BASE64_IMAGE_PAYLOAD_LENGTH = MAX_IMAGE_PAYLOAD_BYTES * 4 / 3 + 4

fun decodeImage(base64ImageDataString: String): DecodedImage {
    val bytes = base64MimeDecoder.decode(base64ImageDataString)
    return DecodedImage(bytes = bytes, extension = detectImageFileExtension(bytes))
}

fun saveImage(image: DecodedImage, outputDir: File): File =
    File(outputDir, "${UUID.randomUUID()}.${image.extension}").apply {
        writeBytes(image.bytes)
    }

internal fun isValidBase64ImagePayloads(base64ImageDataStrings: List<String>): Boolean =
    try {
        require(base64ImageDataStrings.isNotEmpty())
        base64ImageDataStrings.forEach {
            require(it.length <= MAX_BASE64_IMAGE_PAYLOAD_LENGTH) { "payload exceeds size limit" }
            base64MimeDecoder.decode(it)
        }
        true
    } catch (_: IllegalArgumentException) {
        false
    }

internal fun detectImageFileExtension(imageBytes: ByteArray): String {
    // ... (기존 isPng/isJpeg/isWebp/isGif 함수 포함)
}
```

- [ ] **Step 2: Replier.kt에서 이미지 관련 코드 삭제, import 추가**

`Replier.kt`의 `decodeBase64Image`, `detectImageFileExtension`, `isPngSignature`, `isJpegSignature`, `isWebpSignature`, `isGifSignature`, `isValidBase64ImagePayloads`, `base64MimeDecoder`, `MAX_IMAGE_PAYLOAD_BYTES`, `MAX_BASE64_IMAGE_PAYLOAD_LENGTH` 삭제. `ImageEncoder`에서 필요한 함수를 import.

- [ ] **Step 3: 기존 테스트 통과 확인**

Run: `./gradlew test --tests "party.qwer.iris.ReplierImageDetectionTest" -q`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/ImageEncoder.kt \
       app/src/main/java/party/qwer/iris/Replier.kt
git commit -m "refactor: ImageEncoder 추출 (Replier 분해)"
```

---

## Phase 3: 성능 최적화

---

### Task 12: SELECT * 제거 + latestLogId 개선

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/KakaoDB.kt:178-238`

- [ ] **Step 1: latestLogId → SELECT MAX(_id)**

`KakaoDB.kt:178-187`:
```kotlin
// Before
db.rawQuery("SELECT _id FROM chat_logs ORDER BY _id DESC LIMIT 1", null)

// After
db.rawQuery("SELECT MAX(_id) FROM chat_logs", null)
```

- [ ] **Step 2: pollChatLogsAfter → 컬럼 명시**

`KakaoDB.kt:197-204`:
```kotlin
// Before
SELECT *
FROM chat_logs

// After
SELECT _id, id, chat_id, user_id, message, v, created_at, type, thread_id, supplement, attachment
FROM chat_logs
```

- [ ] **Step 3: 빌드 확인**

Run: `./gradlew lint ktlintCheck assembleDebug test -q`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/KakaoDB.kt
git commit -m "perf: SELECT * 제거 + latestLogId MAX(_id) 전환"
```

---

### Task 13: 읽기전용 커넥션 재사용 + sender name 캐시 상향

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/KakaoDB.kt`

- [ ] **Step 1: lazy 읽기전용 커넥션 추가**

`KakaoDB` 클래스에 프로퍼티 추가:
```kotlin
private val readConnectionLock = Any()
private val readConnection: SQLiteDatabase by lazy {
    openDetachedReadConnection()
}

private inline fun <T> withReadConnection(block: (SQLiteDatabase) -> T): T =
    synchronized(readConnectionLock) {
        block(readConnection)
    }
```

- [ ] **Step 2: executeQuery에서 readConnection 사용**

```kotlin
fun executeQuery(sqlQuery: String, bindArgs: Array<String?>?, maxRows: Int = DEFAULT_QUERY_RESULT_LIMIT): List<Map<String, String?>> =
    withReadConnection { conn ->
        readQueryRows(conn, sqlQuery, bindArgs, maxRows.coerceAtLeast(1))
    }
```

`openDetachedReadConnection`의 매번 호출 + close 제거.

- [ ] **Step 3: getNameOfUserId에서 readConnection 사용**

`withPrimaryConnection`을 `withReadConnection`으로 변경 (line 131). `resolveSenderName`의 봇이름 체크는 그대로 유지.

- [ ] **Step 4: closeConnection에서 readConnection 정리**

```kotlin
fun closeConnection() {
    synchronized(readConnectionLock) {
        if (::readConnection.isInitialized && readConnection.isOpen) {
            readConnection.close()
        }
    }
    synchronized(dbLock) {
        if (connection.isOpen) {
            connection.close()
            IrisLogger.info("Database connection closed.")
        }
    }
}
```

lazy 대신 nullable로 변경이 필요할 수 있음. `Lazy`의 `isInitialized`는 `lazy` delegate에서 직접 사용 불가 — nullable `SQLiteDatabase?` 패턴이 더 적합:

```kotlin
@Volatile
private var readConnection: SQLiteDatabase? = null

private fun getOrCreateReadConnection(): SQLiteDatabase =
    readConnection ?: synchronized(readConnectionLock) {
        readConnection ?: openDetachedReadConnection().also { readConnection = it }
    }

private inline fun <T> withReadConnection(block: (SQLiteDatabase) -> T): T =
    synchronized(readConnectionLock) {
        block(getOrCreateReadConnection())
    }
```

- [ ] **Step 5: sender name 캐시 상향**

`ObserverHelper.kt:18`:
```kotlin
// Before
private val senderNameCache = lruMap<Long, String>(64)

// After
private val senderNameCache = lruMap<Long, String>(256)
```

- [ ] **Step 6: 빌드 확인**

Run: `./gradlew lint ktlintCheck assembleDebug test -q`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/KakaoDB.kt \
       app/src/main/java/party/qwer/iris/ObserverHelper.kt
git commit -m "perf: 읽기전용 커넥션 재사용 + sender name 캐시 256 상향"
```

---

### Task 14: Cipher ThreadLocal + SecretKeySpec 캐시 통합

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/KakaoDecrypt.kt:15-17,318-327`

- [ ] **Step 1: keyCache value를 SecretKeySpec으로 변경**

```kotlin
// Before
private val keyCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

// After
private val keyCache = java.util.concurrent.ConcurrentHashMap<String, SecretKeySpec>()
```

`computeIfAbsent` 호출도 변경:
```kotlin
val secretKeySpec = keyCache.computeIfAbsent(saltStr) {
    SecretKeySpec(deriveKey(KEY_BYTES, salt, 2, 32), "AES")
}
```

- [ ] **Step 2: ThreadLocal Cipher 도입**

```kotlin
private val threadLocalCipher = ThreadLocal.withInitial {
    Cipher.getInstance("AES/CBC/NoPadding")
}

// decrypt() 내부:
val cipher = threadLocalCipher.get()
cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
```

`Cipher.getInstance()` 호출 제거.

- [ ] **Step 3: IvParameterSpec 불변 공유**

```kotlin
// companion object 프로퍼티로 한 번만 생성
private val IV_SPEC = IvParameterSpec(IV_BYTES)
```

`decrypt()` 내 `IvParameterSpec(IV_BYTES)` → `IV_SPEC` 참조.

- [ ] **Step 4: 기존 테스트 통과 확인**

Run: `./gradlew test --tests "party.qwer.iris.KakaoDecryptTest" -q`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/KakaoDecrypt.kt
git commit -m "perf: Cipher ThreadLocal 재사용 + SecretKeySpec 캐시 통합"
```

---

### Task 15: H2C maxRequestsPerHost 조정

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/bridge/WebhookHttpClientFactory.kt` (Task 8에서 생성)
- 또는 `app/src/main/java/party/qwer/iris/bridge/H2cDispatcher.kt:68-69` (Task 8 미완료 시)

- [ ] **Step 1: transport별 maxRequestsPerHost 분기**

```kotlin
// WebhookHttpClientFactory 또는 H2cDispatcher에서:
maxRequestsPerHost = when (transport) {
    WebhookTransport.H2C -> 64    // multiplexing
    WebhookTransport.HTTP1 -> 4   // connection limit
}
```

`MAX_CONCURRENT_REQUESTS_PER_HOST` 상수를 transport별 분기로 교체.

- [ ] **Step 2: 기존 테스트 통과 확인**

Run: `./gradlew test --tests "party.qwer.iris.bridge.H2cDispatcherClientConfigTest" -q`
Expected: PASS

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/bridge/WebhookHttpClientFactory.kt  # 또는 H2cDispatcher.kt
git commit -m "perf: H2C maxRequestsPerHost 64 상향 (multiplexing 활용)"
```

---

### Task 16: KakaoProfileIndexer 이중 락 분리

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/KakaoProfileIndexer.kt:70-87`
- Test: `app/src/test/java/party/qwer/iris/KakaoProfileIndexerTest.kt` (기존 테스트 통과 확인)

- [ ] **Step 1: synchronized 내에서 upsert 대상만 수집**

```kotlin
internal fun indexParsedIdentities(identities: Iterable<KakaoNotificationIdentity>) {
    val toUpsert = mutableListOf<KakaoNotificationIdentity>()

    synchronized(this) {
        val activeKeys = HashSet<String>()
        for (identity in identities) {
            activeKeys += identity.notificationKey
            val digest = digest(identity)
            if (lastDigestByNotificationKey[identity.notificationKey] != digest) {
                lastDigestByNotificationKey[identity.notificationKey] = digest
                if (lastDigestByIdentity[identity.stableId] != digest) {
                    toUpsert += identity
                    lastDigestByIdentity[identity.stableId] = digest
                }
            }
        }
        lastDigestByNotificationKey.keys.retainAll(activeKeys)
    }

    // DB write는 락 밖에서
    for (identity in toUpsert) {
        profileStore.upsert(identity)
    }
}
```

- [ ] **Step 2: 기존 테스트 통과 확인**

Run: `./gradlew test --tests "party.qwer.iris.KakaoProfileIndexerTest" -q`
Expected: PASS

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/KakaoProfileIndexer.kt
git commit -m "perf: KakaoProfileIndexer 이중 락 분리 (upsert를 synchronized 밖으로)"
```

### Task 16.5: 이미지 이중 디코딩 제거

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/ImageEncoder.kt` (Task 11에서 생성)
- Modify: `app/src/main/java/party/qwer/iris/Replier.kt`
- Modify: `app/src/main/java/party/qwer/iris/ReplyAdmission.kt`

- [ ] **Step 1: Replier.sendMultiplePhotos에서 DecodedImage 전달 경로 구성**

현재 흐름: `isValidBase64ImagePayloads()`에서 전체 decode (1회) → `sendImages()`의 `prepareImagesInternal()`에서 다시 `decodeBase64Image()` (2회)

새 흐름: admission 단계에서 `decodeImage()` 호출 → `List<DecodedImage>` 반환 → `sendImages()`에 전달 → `ImageEncoder.save()` 호출

```kotlin
// Replier.kt — sendMultiplePhotos 변경
fun sendMultiplePhotos(room: Long, base64ImageDataStrings: List<String>): ReplyAdmissionResult {
    val decodedImages = try {
        base64ImageDataStrings.map { base64 ->
            require(base64.length <= MAX_BASE64_IMAGE_PAYLOAD_LENGTH) { "payload exceeds size limit" }
            decodeImage(base64)
        }
    } catch (_: IllegalArgumentException) {
        return ReplyAdmissionResult(ReplyAdmissionStatus.INVALID_PAYLOAD, "image replies require valid base64 payload")
    }

    return enqueueRequest(SendMessageRequest { sendDecodedImages(room, decodedImages) })
}
```

- [ ] **Step 2: sendDecodedImages 구현 (기존 sendImages 대체)**

```kotlin
private fun sendDecodedImages(room: Long, decodedImages: List<DecodedImage>) {
    ensureImageDir(imageDir)
    val uris = ArrayList<Uri>(decodedImages.size)
    val createdFiles = ArrayList<File>(decodedImages.size)
    try {
        decodedImages.forEach { image ->
            val imageFile = saveImage(image, imageDir)
            createdFiles.add(imageFile)
            val imageUri = Uri.fromFile(imageFile)
            if (imageMediaScanEnabled) mediaScan(imageUri)
            uris.add(imageUri)
        }
        require(uris.isNotEmpty()) { "no image URIs created" }
        sendPreparedImages(PreparedImages(room = room, uris = uris, files = createdFiles))
    } catch (e: Exception) {
        createdFiles.forEach { file -> if (file.exists()) file.delete() }
        throw e
    }
}
```

- [ ] **Step 3: 기존 isValidBase64ImagePayloads 및 decodeBase64Image 삭제**

`ImageEncoder.kt`에서 `isValidBase64ImagePayloads` 삭제 (더 이상 사용하지 않음).
`Replier.kt`의 기존 `sendImages`, `prepareImages`, `prepareImagesInternal` 삭제.

- [ ] **Step 4: 빌드 및 테스트 확인**

Run: `./gradlew lint ktlintCheck assembleDebug test -q`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/ImageEncoder.kt \
       app/src/main/java/party/qwer/iris/Replier.kt
git commit -m "perf: 이미지 이중 디코딩 제거 (decode 1회 → DecodedImage 전달)"
```

---

## Phase 4: 인터페이스 추출 + 테스트

> Phase 4는 가장 광범위한 변경입니다. Task 17-18은 단독 브랜치에서 진행을 권장합니다.

---

### Task 17: ConfigProvider 인터페이스 추출 + ConfigManager 전환

**Files:**
- Create: `app/src/main/java/party/qwer/iris/ConfigProvider.kt`
- Modify: `app/src/main/java/party/qwer/iris/Configurable.kt` (companion → class)
- Modify: `app/src/main/java/party/qwer/iris/Main.kt` (인스턴스 생성 + 주입)
- Modify: 모든 `Configurable.*` 직접 참조 파일 (DBObserver, ObserverHelper, KakaoDB, H2cDispatcher, IrisServer, Replier)

> 이 Task는 변경 파일이 많아 단독 브랜치에서 진행을 권장합니다.

- [ ] **Step 1: ConfigProvider 인터페이스 정의**

```kotlin
package party.qwer.iris

interface ConfigProvider {
    val botId: Long
    val botName: String
    val botSocketPort: Int
    val botToken: String
    val webhookToken: String
    val dbPollingRate: Long
    val messageSendRate: Long
    fun webhookEndpointFor(route: String): String
}
```

- [ ] **Step 2: Configurable → ConfigManager 클래스 전환**

주요 변환 포인트:
- `class Configurable { companion object { ... } }` → `class ConfigManager(configPath: String) : ConfigProvider`
- `companion object` 내의 모든 프로퍼티/메서드를 인스턴스 멤버로 이동
- `init` 블록에서 `loadConfig()` 호출 유지
- **Shutdown hook 제거** — `Runtime.getRuntime().addShutdownHook()` 삭제. `Main.kt`가 shutdown 시 `saveConfigNow()` 호출
- `onMessageSendRateChanged` 콜백 유지 (인스턴스 프로퍼티로 이동)
- `saveConfigNow()`, `configResponse()`, `configUpdateResponse()` 등 mutation 메서드는 `ConfigProvider` 인터페이스에 포함하지 않고 `ConfigManager`에만 노출
- 환경변수 폴백 (`IRIS_BOT_TOKEN`, `IRIS_WEBHOOK_TOKEN`)은 기존 동작 유지

- [ ] **Step 3: Main.kt에서 인스턴스 생성, 생성자 주입 체인 구성**

```kotlin
// Main.kt
val configManager = ConfigManager(configPath)
val kakaoDB = KakaoDB(configManager)
val observerHelper = ObserverHelper(kakaoDB, configManager)
val dbObserver = DBObserver(observerHelper, configManager)
val irisServer = IrisServer(kakaoDB, notificationReferer, configManager)
// ...
```

**Shutdown ownership 원칙:** `Main.kt`의 shutdown hook이 순서대로:
1. `dbObserver.stopPolling()`
2. `irisServer.stopServer()`
3. `configManager.saveConfigNow()`
4. `kakaoDB.closeConnection()`

- [ ] **Step 4: 모든 정적 참조를 생성자 주입으로 전환**

`Configurable.botId` → `config.botId`, `Configurable.dbPollingRate` → `config.dbPollingRate` 등. 대상 파일:
- `DBObserver.kt` (dbPollingRate)
- `ObserverHelper.kt` (botId via isOwnBotMessage)
- `KakaoDB.kt` (botId, botName)
- `H2cDispatcher.kt` (webhookEndpointFor, webhookToken)
- `IrisServer.kt` (botSocketPort, botToken, configResponse 등)
- `Replier.kt` (messageSendRate)
- `ChatLogDecryptor.kt` (botId)

- [ ] **Step 5: 전체 빌드 + 테스트 통과 확인**

Run: `./gradlew lint ktlintCheck assembleDebug test -q`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "refactor: Configurable companion → ConfigManager 인스턴스 + ConfigProvider DI"
```

---

### Task 18: MessageSender 인터페이스 추출 + ReplyService 전환

**Files:**
- Create: `app/src/main/java/party/qwer/iris/MessageSender.kt`
- Modify: `app/src/main/java/party/qwer/iris/Replier.kt` (companion → class)
- Modify: `app/src/main/java/party/qwer/iris/IrisServer.kt`, `Main.kt`, `ReplyAdmission.kt`

- [ ] **Step 1: MessageSender 인터페이스 정의**

```kotlin
package party.qwer.iris

interface MessageSender {
    fun sendMessage(referer: String, chatId: Long, msg: String, threadId: Long?, threadScope: Int?): ReplyAdmissionResult
    fun sendPhoto(room: Long, base64ImageDataString: String): ReplyAdmissionResult
    fun sendMultiplePhotos(room: Long, base64ImageDataStrings: List<String>): ReplyAdmissionResult
}
```

- [ ] **Step 2: Replier companion → ReplyService class 전환**

주요 변환 포인트:
- `class Replier { companion object { ... } }` → `class ReplyService(config: ConfigProvider) : MessageSender`
- `messageChannel`, `messageSenderJob`, `coroutineScope`를 인스턴스 프로퍼티로 이동
- `startMessageSender()`, `restartMessageSender()`, `shutdown()` 라이프사이클 메서드 유지
- `messageSendRate` 참조를 `config.messageSendRate`로 변경
- `onMessageSendRateChanged` 콜백을 `Main.kt`에서 `configManager.onMessageSendRateChanged = { replyService.restartMessageSender() }` 로 연결
- `imageDir`, `imageMediaScanEnabled`은 인스턴스 프로퍼티로 이동

- [ ] **Step 3: Main.kt, IrisServer, ReplyAdmission 주입 변경**

```kotlin
// Main.kt
val replyService = ReplyService(configManager)
replyService.startMessageSender()
configManager.onMessageSendRateChanged = { replyService.restartMessageSender() }
val irisServer = IrisServer(kakaoDB, notificationReferer, configManager, replyService)
```

Shutdown hook에 `replyService.shutdown()` 추가 (irisServer.stopServer 이후, configManager.saveConfigNow 이전).

`ReplyAdmission.kt`의 `admitReply()`에서 `Replier.sendMessage` → `messageSender.sendMessage` 변경.

- [ ] **Step 4: 전체 빌드 + 테스트 통과 확인**

Run: `./gradlew lint ktlintCheck assembleDebug test -q`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "refactor: Replier companion → ReplyService 인스턴스 + MessageSender DI"
```

---

### Task 19: ChatLogRepository / ProfileRepository 인터페이스 추출

**Files:**
- Create: `app/src/main/java/party/qwer/iris/ChatLogRepository.kt`
- Create: `app/src/main/java/party/qwer/iris/ProfileRepository.kt`
- Modify: `app/src/main/java/party/qwer/iris/KakaoDB.kt`
- Modify: `app/src/main/java/party/qwer/iris/ObserverHelper.kt`, `IrisServer.kt`, `KakaoProfileIndexer.kt`

- [ ] **Step 1: ChatLogRepository 인터페이스 정의**

```kotlin
package party.qwer.iris

interface ChatLogRepository {
    fun pollChatLogsAfter(afterLogId: Long, limit: Int = 100): List<KakaoDB.ChatLogEntry>
    fun resolveSenderName(userId: Long): String
    fun resolveRoomMetadata(chatId: Long): KakaoDB.RoomMetadata
    fun latestLogId(): Long
    fun executeQuery(sqlQuery: String, bindArgs: Array<String?>?, maxRows: Int = 500): List<Map<String, String?>>
}
```

- [ ] **Step 2: ProfileRepository 인터페이스 정의**

```kotlin
package party.qwer.iris

interface ProfileRepository {
    fun upsertObservedProfile(identity: KakaoNotificationIdentity)
}
```

- [ ] **Step 3: KakaoDB에 인터페이스 구현 선언 추가**

```kotlin
class KakaoDB(private val config: ConfigProvider) : ChatLogRepository, ProfileRepository {
    // 기존 메서드 시그니처 유지, override 추가
}
```

- [ ] **Step 4: 소비자 생성자를 인터페이스 타입으로 변경**

- `ObserverHelper(db: KakaoDB)` → `ObserverHelper(db: ChatLogRepository, config: ConfigProvider)`
- `IrisServer(kakaoDB: KakaoDB, ...)` → `IrisServer(chatLogRepo: ChatLogRepository, ...)`
- `KakaoDbNotificationIdentityStore(kakaoDb: KakaoDB)` → `KakaoDbNotificationIdentityStore(profileRepo: ProfileRepository)`
  - 이미 `NotificationIdentityStore` 인터페이스가 있으므로 `KakaoProfileIndexer`는 변경 불필요

- [ ] **Step 5: Main.kt 주입 변경**

`KakaoDB` 인스턴스를 `ChatLogRepository`와 `ProfileRepository`로 전달:
```kotlin
val kakaoDB = KakaoDB(configManager)
val observerHelper = ObserverHelper(kakaoDB, configManager)  // ChatLogRepository
val identityStore = KakaoDbNotificationIdentityStore(kakaoDB) // ProfileRepository
```

- [ ] **Step 6: 전체 빌드 + 테스트 통과 확인**

Run: `./gradlew lint ktlintCheck assembleDebug test -q`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "refactor: ChatLogRepository / ProfileRepository 인터페이스 추출 + KakaoDB DI"
```

---

### Task 20: 추출 클래스 테스트 확충

**Files:**
- Create: `app/src/test/java/party/qwer/iris/QuerySanitizerTest.kt`
- Create: `app/src/test/java/party/qwer/iris/ConfigUpdateHandlerTest.kt`
- Create: `app/src/test/java/party/qwer/iris/bridge/WebhookPayloadBuilderTest.kt`
- Create: `app/src/test/java/party/qwer/iris/ImageEncoderTest.kt`
- Create: `app/src/test/java/party/qwer/iris/BotIdentityDetectorTest.kt`
- Create: `app/src/test/java/party/qwer/iris/ChatLogDecryptorTest.kt`

- [ ] **Step 1: QuerySanitizerTest 작성**

기존 `IrisServerQueryValidationTest.kt`의 `isReadOnlyQuery` 테스트 케이스를 `QuerySanitizerTest.kt`로 이동. 기존 파일 삭제. 추가 케이스: SQL injection 패턴 (`'; DROP TABLE --`), nested WITH...SELECT, 빈 입력, pragma 화이트리스트 외 pragma 거부.

- [ ] **Step 2: ConfigUpdateHandlerTest 작성 (신규)**

fixture: `ConfigRequest` 인스턴스. 케이스: 유효 endpoint/dbrate/sendrate/botport, 경계값 (port 0/65536, rate -1), 잘못된 타입 (rate null), unknown config name, endpoint 프로토콜 검증.

- [ ] **Step 3: WebhookPayloadBuilderTest 작성 (신규)**

fixture: `RoutingCommand` 인스턴스. 케이스: 필수 필드만 있는 minimal command, 모든 optional 필드 포함, thread metadata 포함/미포함, 특수문자 (`"`, `\n`) 이스케이프 확인, attachment 포함.

- [ ] **Step 4: ImageEncoderTest 작성**

기존 `ReplierImageDetectionTest.kt`의 포맷 감지 테스트를 `ImageEncoderTest.kt`로 이동. 기존 파일 삭제. 추가 케이스: `decodeImage()` 통합 테스트, 손상된 Base64, 빈 입력, unknown 포맷 → `.img` 확장자.

- [ ] **Step 5: BotIdentityDetectorTest 작성 (신규)**

기존 `KakaoDBBotUserIdResolutionTest.kt`는 `resolveBotUserId` 로직 테스트 — `BotIdentityDetector.kt`로 이동했으므로 파일명을 `BotIdentityDetectorTest.kt`로 rename. 추가 케이스 불필요 (기존 커버리지 충분).

- [ ] **Step 6: ChatLogDecryptorTest 작성 (신규)**

fixture: `Map<String, String?>` (mock row). 케이스: message+attachment 복호화, enc 필드 없는 row (no-op), profile 필드 복호화, botId=0일 때 skip, 잘못된 v JSON, 빈 row.
`KakaoDecrypt`는 실제 호출 (JVM에서 동작하므로 mock 불필요).

- [ ] **Step 7: 전체 테스트 통과 확인**

Run: `./gradlew test -q`
Expected: PASS

- [ ] **Step 8: 커밋**

```bash
git add app/src/test/
git commit -m "test: 추출 클래스 단위 테스트 확충 (6개 파일)"
```

---

## 검증 체크리스트

매 Task 완료 시:
- [ ] `./gradlew lint ktlintCheck assembleDebug test` 통과
- [ ] ktlint 위반 시 `./gradlew ktlintFormat` 후 재확인
- [ ] 기존 public API 시그니처 유지 여부 확인
- [ ] 리팩토링과 기능 변경 혼합 없음
