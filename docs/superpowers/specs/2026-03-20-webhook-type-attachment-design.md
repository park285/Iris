# Webhook Payload: messageType + attachment 필드 추가

## 배경

irispy-client(Python)는 WebSocket 경로로 카카오톡 메시지 타입과 attachment 데이터를 수신하여 이미지 URL 추출, 타입별 분기 등에 활용하고 있다. 현재 Iris 서버의 webhook payload에는 이 두 필드가 포함되지 않아 Go 클라이언트 소비자가 동일한 기능을 사용할 수 없다.

`KakaoDB.ChatLogEntry`에 `messageType`은 이미 매핑되어 있고, `attachment` 컬럼은 `SELECT *`로 읽히지만 `ChatLogEntry`에 매핑되지 않는 상태이다.

## 결정 사항

| 항목 | 결정 |
|------|------|
| attachment 포함 조건 | 항상 포함 (값이 없으면 필드 생략 — 기존 optional 패턴) |
| attachment 복호화 | 하지 않음 — 암호화된 원본 그대로 전송. 소비자가 `/decrypt` 엔드포인트로 필요 시 복호화 |
| webhook JSON key | `"type"` (messageType), `"attachment"` |

## 변경 범위

### 1. `KakaoDB.kt` — `ChatLogEntry`에 `attachment` 필드 추가

**파일:** `app/src/main/java/party/qwer/iris/KakaoDB.kt`

`ChatLogEntry` data class에 `attachment` 필드 추가:

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
    val attachment: String? = null,   // 추가
)
```

`pollChatLogsAfter`에서 `attachment` 컬럼 인덱스 매핑 추가. 기존 `supplementIndex` 패턴과 동일:

```kotlin
val attachmentIndex = cursor.getColumnIndex("attachment")
```

`ChatLogEntry` 생성 시:

```kotlin
attachment = cursor.getOptionalString(attachmentIndex),
```

**위치 참조:**
- `ChatLogEntry` 정의: 354-365행
- `pollChatLogsAfter` 컬럼 인덱스: 202-211행
- `ChatLogEntry` 생성: 213-226행

### 2. `RoutingModels.kt` — `RoutingCommand`에 필드 추가

**파일:** `app/src/main/java/party/qwer/iris/bridge/RoutingModels.kt`

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
    val messageType: String? = null,   // 추가
    val attachment: String? = null,    // 추가
)
```

### 3. `ObserverHelper.kt` — `routeCommand`에서 새 필드 전달

**파일:** `app/src/main/java/party/qwer/iris/ObserverHelper.kt`

`routeCommand` 함수(180-216행)의 `RoutingCommand` 생성 부분에 두 필드 추가:

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
        messageType = logEntry.messageType?.trim()?.takeIf { it.isNotEmpty() },  // 추가
        attachment = logEntry.attachment?.takeIf { it.isNotBlank() },              // 추가 (trim 없음 — 암호화 원본 보존)
    )
```

**주의:** `attachment`는 복호화하지 않는다. 암호화된 원본 그대로 전달.

### 4. `H2cDispatcher.kt` — `buildQueuedDelivery`에 필드 추가

**파일:** `app/src/main/java/party/qwer/iris/bridge/H2cDispatcher.kt`

`buildQueuedDelivery` 함수(390-424행)의 `buildJsonObject` 블록에 추가. 기존 optional 필드 패턴(`isNullOrBlank` 체크 후 `put`)을 따른다:

```kotlin
// threadScope 뒤에 추가
if (!command.messageType.isNullOrBlank()) {
    put("type", command.messageType)
}
if (!command.attachment.isNullOrBlank()) {
    put("attachment", command.attachment)
}
```

**JSON key 이름:** `messageType`이 아닌 `"type"`을 사용한다. 카카오톡 DB 원본 컬럼명과 일치하며, irispy-client에서도 `type`으로 참조한다.

## 변경되는 Webhook JSON Payload

변경 전:
```json
{
  "route": "hololive",
  "messageId": "kakao-log-12345-hololive",
  "sourceLogId": 12345,
  "text": "!명령어",
  "room": "67890",
  "sender": "사용자",
  "userId": "11111",
  "chatLogId": "abc",
  "roomType": "OD",
  "threadId": "thread-1",
  "threadScope": 2
}
```

변경 후 (type, attachment가 있는 경우):
```json
{
  "route": "hololive",
  "messageId": "kakao-log-12345-hololive",
  "sourceLogId": 12345,
  "text": "!명령어",
  "room": "67890",
  "sender": "사용자",
  "userId": "11111",
  "chatLogId": "abc",
  "roomType": "OD",
  "threadId": "thread-1",
  "threadScope": 2,
  "type": "1",
  "attachment": "{encrypted-attachment-data}"
}
```

일반 텍스트 메시지(type=1, attachment 없음):
```json
{
  "route": "hololive",
  "messageId": "kakao-log-12345-hololive",
  "sourceLogId": 12345,
  "text": "!명령어",
  "room": "67890",
  "sender": "사용자",
  "userId": "11111",
  "type": "1"
}
```

## 하위 호환성

- 새 필드는 모두 optional (null이면 JSON에서 생략)
- 기존 소비자 중 tolerant JSON decoder(`encoding/json` 등 unknown field 무시)를 사용하는 경우 영향 없음
- strict JSON schema 검증을 사용하는 소비자는 스키마 업데이트 필요
- Go 클라이언트의 `WebhookRequest`에도 `omitempty` 태그로 대응 예정 (별도 스펙)

## 테스트

- `H2cDispatcherThreadPayloadTest.kt` — `messageType`/`attachment`가 payload에 포함/생략되는 케이스 추가
- `RoutingModelsTest.kt` — `RoutingCommand`에 새 필드가 정상 설정되는지 확인
- 기존 테스트는 새 필드 미설정(default null)이므로 변경 불필요
