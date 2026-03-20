# Webhook Payload: messageType + attachment 필드 추가 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Webhook payload에 카카오톡 메시지 타입(`type`)과 `attachment` 필드를 추가하여 Go 클라이언트가 메시지 타입별 분기와 첨부 데이터를 활용할 수 있게 한다.

**Architecture:** `ChatLogEntry` → `RoutingCommand` → `H2cDispatcher` JSON payload 파이프라인을 따라 두 필드를 전파한다. `messageType`은 이미 `ChatLogEntry`에 존재하지만 `ObserverHelper`부터 `RoutingCommand`로의 전파가 필요. `attachment`는 `ChatLogEntry`부터 새로 추가. 복호화하지 않고 암호화된 원본 그대로 전달.

**Tech Stack:** Kotlin, kotlinx.serialization (buildJsonObject), JVM 테스트 (kotlin-test-junit, com.sun.net.httpserver)

**Spec:** `docs/superpowers/specs/2026-03-20-webhook-type-attachment-design.md`

---

### Task 1: `ChatLogEntry`에 `attachment` 필드 추가 + DB 매핑

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/KakaoDB.kt:354-365` (ChatLogEntry data class)
- Modify: `app/src/main/java/party/qwer/iris/KakaoDB.kt:202-225` (pollChatLogsAfter 컬럼 매핑)

- [ ] **Step 1: `ChatLogEntry` data class에 `attachment` 필드 추가**

`app/src/main/java/party/qwer/iris/KakaoDB.kt:364` — `supplement` 필드 뒤에 추가:

```kotlin
data class ChatLogEntry(
    val id: Long,
    val chatLogId: String? = null,
    val chatId: Long,
    val userId: Long,
    val message: String,
    val metadata: String,
    val createdAt: String?,
    val messageType: String? = null,
    val threadId: String? = null,
    val supplement: String? = null,
    val attachment: String? = null,
)
```

- [ ] **Step 2: `pollChatLogsAfter`에서 `attachment` 컬럼 인덱스 추가**

`app/src/main/java/party/qwer/iris/KakaoDB.kt:211` — `supplementIndex` 뒤에 추가:

```kotlin
val attachmentIndex = cursor.getColumnIndex("attachment")
```

- [ ] **Step 3: `ChatLogEntry` 생성 시 `attachment` 매핑 추가**

`app/src/main/java/party/qwer/iris/KakaoDB.kt:224` — `supplement` 매핑 뒤에 추가:

```kotlin
attachment = cursor.getOptionalString(attachmentIndex),
```

- [ ] **Step 4: 빌드 확인**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```
feat(db): ChatLogEntry에 attachment 필드 추가

pollChatLogsAfter에서 attachment 컬럼을 읽어 ChatLogEntry에 매핑.
암호화된 원본 그대로 유지 — 복호화는 소비자 측 처리.
```

---

### Task 2: `RoutingCommand`에 `messageType`, `attachment` 필드 추가

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/bridge/RoutingModels.kt:3-14` (RoutingCommand data class)

- [ ] **Step 1: `RoutingCommand`에 두 필드 추가**

`app/src/main/java/party/qwer/iris/bridge/RoutingModels.kt:13` — `threadScope` 뒤에 추가:

```kotlin
data class RoutingCommand(
    val text: String,
    val room: String,
    val sender: String,
    val userId: String,
    val sourceLogId: Long,
    val chatLogId: String? = null,
    val roomType: String? = null,
    val roomLinkId: String? = null,
    val threadId: String? = null,
    val threadScope: Int? = null,
    val messageType: String? = null,
    val attachment: String? = null,
)
```

- [ ] **Step 2: 빌드 확인**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (기존 코드는 named parameter 사용, default null이므로 호환됨)

- [ ] **Step 3: Commit**

```
feat(bridge): RoutingCommand에 messageType, attachment 필드 추가

optional 필드(default null)로 추가하여 기존 호출부 호환성 유지.
```

---

### Task 3: `ObserverHelper`에서 새 필드 전달

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/ObserverHelper.kt:187-199` (routeCommand 내 RoutingCommand 생성)

- [ ] **Step 1: `routeCommand`의 `RoutingCommand` 생성에 두 필드 추가**

`app/src/main/java/party/qwer/iris/ObserverHelper.kt:198-199` — `threadScope` 뒤에 추가:

```kotlin
val routingCommand =
    RoutingCommand(
        text = parsedCommand.normalizedText,
        room = logEntry.chatId.toString(),
        sender = resolveSenderName(logEntry.userId),
        userId = logEntry.userId.toString(),
        sourceLogId = logEntry.id,
        chatLogId = logEntry.chatLogId?.trim()?.takeIf { it.isNotEmpty() },
        roomType = roomMetadata.type.takeIf { it.isNotEmpty() },
        roomLinkId = roomMetadata.linkId.takeIf { it.isNotEmpty() },
        threadId = threadMetadata?.threadId,
        threadScope = threadMetadata?.threadScope,
        messageType = logEntry.messageType?.trim()?.takeIf { it.isNotEmpty() },
        attachment = logEntry.attachment?.takeIf { it.isNotBlank() },
    )
```

주의: `attachment`는 복호화하지 않는다. 암호화된 원본 그대로 전달.

- [ ] **Step 2: 빌드 확인**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```
feat(observer): webhook 라우팅에 messageType, attachment 전달

ChatLogEntry의 messageType과 attachment를 RoutingCommand로 전파.
attachment는 암호화된 원본 유지.
```

---

### Task 4: `H2cDispatcher`에서 JSON payload에 필드 추가

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/bridge/H2cDispatcher.kt:418-422` (buildQueuedDelivery 내 buildJsonObject)

- [ ] **Step 1: `buildQueuedDelivery`의 `buildJsonObject`에 두 필드 추가**

`app/src/main/java/party/qwer/iris/bridge/H2cDispatcher.kt:421` — `threadScope` 뒤, `.toString()` 전에 추가:

```kotlin
command.threadScope?.let { put("threadScope", it) }
if (!command.messageType.isNullOrBlank()) {
    put("type", command.messageType)
}
if (!command.attachment.isNullOrBlank()) {
    put("attachment", command.attachment)
}
```

JSON key: `messageType`가 아닌 `"type"` 사용 (카카오톡 DB 원본 컬럼명, irispy-client 호환).

- [ ] **Step 2: 빌드 확인**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```
feat(dispatcher): webhook payload에 type, attachment 필드 추가

JSON key "type"은 카카오톡 DB 원본 컬럼명과 일치.
optional 필드 — null/blank이면 생략하여 하위 호환성 유지.
```

---

### Task 5: 테스트 — `RoutingCommand` 새 필드 보존 확인

**Files:**
- Modify: `app/src/test/java/party/qwer/iris/bridge/RoutingModelsTest.kt`

- [ ] **Step 1: `RoutingModelsTest`에 새 필드 보존 테스트 추가**

`app/src/test/java/party/qwer/iris/bridge/RoutingModelsTest.kt` — 기존 테스트 뒤에 추가:

```kotlin
@Test
fun `routing command preserves optional message type and attachment`() {
    val command =
        RoutingCommand(
            text = "!ping",
            room = "room-1",
            sender = "tester",
            userId = "user-1",
            sourceLogId = 42L,
            messageType = "1",
            attachment = "{encrypted-data}",
        )

    assertEquals("1", command.messageType)
    assertEquals("{encrypted-data}", command.attachment)
}

@Test
fun `routing command defaults message type and attachment to null`() {
    val command =
        RoutingCommand(
            text = "!ping",
            room = "room-1",
            sender = "tester",
            userId = "user-1",
            sourceLogId = 42L,
        )

    assertEquals(null, command.messageType)
    assertEquals(null, command.attachment)
}
```

- [ ] **Step 2: 테스트 실행**

Run: `./gradlew test --tests "party.qwer.iris.bridge.RoutingModelsTest" 2>&1 | tail -20`
Expected: 4 tests PASSED

- [ ] **Step 3: Commit**

```
test(bridge): RoutingCommand messageType, attachment 필드 보존 테스트

설정 시 값 보존 및 미설정 시 null default 확인.
```

---

### Task 6: 테스트 — webhook payload에 `type`, `attachment` 포함/생략

**Files:**
- Modify: `app/src/test/java/party/qwer/iris/bridge/H2cDispatcherThreadPayloadTest.kt`

- [ ] **Step 1: 기존 테스트에 `messageType`, `attachment` assertion 추가**

기존 `includes thread metadata in webhook payload` 테스트의 `RoutingCommand`에 새 필드를 추가하고 assertion 추가:

`app/src/test/java/party/qwer/iris/bridge/H2cDispatcherThreadPayloadTest.kt:42-53` — `RoutingCommand` 생성에 추가:

```kotlin
RoutingCommand(
    text = "!질문 hi",
    room = "1",
    sender = "tester",
    userId = "1",
    sourceLogId = 42L,
    chatLogId = "3796822474849894401",
    roomType = "OD",
    roomLinkId = "435751742",
    threadId = "12345",
    threadScope = 2,
    messageType = "1",
    attachment = "{encrypted-data}",
)
```

assertion 추가 (62행 뒤):

```kotlin
assertTrue(requestBody.get().contains(""""type":"1""""))
assertTrue(requestBody.get().contains(""""attachment":"{encrypted-data}""""))
```

- [ ] **Step 2: `type`과 `attachment`가 null일 때 생략되는 테스트 추가**

같은 파일에 새 테스트 메서드 추가:

```kotlin
@Test
fun `omits type and attachment from payload when not provided`() {
    val port = reservePort()
    val endpoint = "http://127.0.0.1:$port/webhook/iris"
    Configurable.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, endpoint)

    val requestBody = AtomicReference("")
    val requestLatch = CountDownLatch(1)
    val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
            createContext(
                "/webhook/iris",
                CapturingHandler(requestBody, requestLatch),
            )
            executor = Executors.newSingleThreadExecutor()
            start()
        }

    try {
        H2cDispatcher(transportOverride = "http1").use { dispatcher ->
            assertEquals(
                RoutingResult.ACCEPTED,
                dispatcher.route(
                    RoutingCommand(
                        text = "!질문 hi",
                        room = "1",
                        sender = "tester",
                        userId = "1",
                        sourceLogId = 43L,
                    ),
                ),
            )

            assertTrue(requestLatch.await(3, TimeUnit.SECONDS))
            val body = requestBody.get()
            assertTrue(!body.contains(""""type":"""))
            assertTrue(!body.contains(""""attachment":"""))
        }
    } finally {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }
}
```

- [ ] **Step 3: 테스트 실행**

Run: `./gradlew test --tests "party.qwer.iris.bridge.H2cDispatcherThreadPayloadTest" 2>&1 | tail -20`
Expected: 2 tests PASSED

- [ ] **Step 4: Commit**

```
test(dispatcher): type, attachment payload 포함/생략 테스트 추가

포함 케이스: 기존 thread metadata 테스트에 type, attachment assertion 추가.
생략 케이스: messageType/attachment 미설정 시 JSON에서 제외 확인.
```
