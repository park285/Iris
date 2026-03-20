# Webhook Payload 변경 안내: `type`, `attachment` 필드 추가

**Iris 버전:** `feature/webhook-type-attachment` branch (2026-03-20)
**영향 범위:** 모든 webhook 소비자 (Go, Python 등)

---

## 요약

Webhook JSON payload에 두 개의 **optional** 필드가 추가됩니다.

| 필드 | 타입 | 설명 |
|------|------|------|
| `type` | `string` | 카카오톡 메시지 타입 코드 (없으면 생략) |
| `attachment` | `string` | 첨부 메타데이터 — **암호화된 원본** (없으면 생략) |

**하위 호환:** 두 필드 모두 optional이며, 값이 없으면 JSON에서 생략됩니다. `encoding/json` 등 unknown field를 무시하는 decoder를 사용 중이라면 코드 변경 없이 동작합니다.

---

## Payload 예시

### 텍스트 메시지 (type 있음, attachment 없음)

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

### 이미지 메시지 (type + attachment 모두 있음)

```json
{
  "route": "hololive",
  "messageId": "kakao-log-12346-hololive",
  "sourceLogId": 12346,
  "text": "!명령어",
  "room": "67890",
  "sender": "사용자",
  "userId": "11111",
  "chatLogId": "abc",
  "roomType": "OD",
  "type": "2",
  "attachment": "{암호화된 JSON 문자열}"
}
```

### 기존 동작 (type/attachment 없음 — 구버전 Iris 또는 값 없는 경우)

```json
{
  "route": "hololive",
  "messageId": "kakao-log-12347-hololive",
  "sourceLogId": 12347,
  "text": "!명령어",
  "room": "67890",
  "sender": "사용자",
  "userId": "11111"
}
```

---

## 전체 Payload 스키마

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `route` | string | O | webhook route 이름 (`hololive`, `chatbotgo`, `settlement`) |
| `messageId` | string | O | `kakao-log-{sourceLogId}-{route}` |
| `sourceLogId` | number | O | chat_logs `_id` |
| `text` | string | O | 명령어 텍스트 (prefix 제거 후) |
| `room` | string | O | chat_id |
| `sender` | string | O | 발신자 이름 |
| `userId` | string | O | 발신자 user_id |
| `chatLogId` | string | - | chat_logs `id` 컬럼 |
| `roomType` | string | - | `"OD"` (1:1), `"MultiChat"` 등 |
| `roomLinkId` | string | - | 오픈채팅 link_id |
| `threadId` | string | - | thread id |
| `threadScope` | number | - | thread scope (1=채팅방 전체, 2+=스레드) |
| `type` | string | - | **신규.** 메시지 타입 코드 |
| `attachment` | string | - | **신규.** 암호화된 첨부 메타데이터 JSON |

---

## 메시지 타입 코드 (`type`)

| 코드 | 타입 |
|------|------|
| `1` | 텍스트 |
| `2` | 사진 |
| `3` | 동영상 |
| `5` | 위치(지도) |
| `6` | 음성메시지 |
| `12` | 음악 공유 |
| `13` | 커스텀 이모티콘 |
| `14` | 스티커 |
| `15` | 파일 |
| `16` | URL/링크 공유 |
| `23` | 사진 여러장 (앨범) |
| `26` | 답장 (인용) |
| `27` | 오픈채널 게시글 |
| `71` | 라이브톡 |

값은 문자열(`"1"`, `"2"`)로 전달됩니다. 정수가 아닙니다.

---

## attachment 복호화

`attachment` 값은 **암호화된 원본**입니다. 클라이언트에서 직접 파싱할 수 없습니다.

### 방법 1: Iris `/query` 엔드포인트로 복호화

```
POST /query
X-Bot-Token: {botToken}
Content-Type: application/json

{
  "query": "SELECT * FROM chat_logs WHERE _id = ?",
  "bind": [12345]
}
```

응답의 `attachment` 필드가 **자동 복호화**되어 반환됩니다:

```json
{
  "rowCount": 1,
  "data": [
    {
      "_id": "12345",
      "attachment": "{\"url\":\"http://...\",\"size\":12345,\"width\":640,\"height\":480}"
    }
  ]
}
```

### 방법 2: 클라이언트에서 직접 복호화

kakaodecrypt 알고리즘 사용 (AES/CBC + PBKDF2). `userId`와 `enc` 값(metadata `v` JSON의 `enc` 키)이 필요합니다. 참고: [jiru/kakaodecrypt](https://github.com/nicholaschiasson/kakaodecrypt)

### 복호화 후 attachment 예시 (사진)

```json
{
  "url": "http://dn-m.talk.kakao.com/talkm/...",
  "size": 123456,
  "width": 1080,
  "height": 1920,
  "type": "image/jpeg",
  "cs": "aes",
  "s": 256
}
```

---

## Go 클라이언트 대응

### struct 변경

```go
type WebhookRequest struct {
    Route       string `json:"route"`
    MessageID   string `json:"messageId"`
    SourceLogID int64  `json:"sourceLogId"`
    Text        string `json:"text"`
    Room        string `json:"room"`
    Sender      string `json:"sender"`
    UserID      string `json:"userId"`
    ChatLogID   string `json:"chatLogId,omitempty"`
    RoomType    string `json:"roomType,omitempty"`
    RoomLinkID  string `json:"roomLinkId,omitempty"`
    ThreadID    string `json:"threadId,omitempty"`
    ThreadScope *int   `json:"threadScope,omitempty"`
    Type        string `json:"type,omitempty"`        // 추가
    Attachment  string `json:"attachment,omitempty"`   // 추가
}
```

### 타입별 분기 예시

```go
func handleWebhook(req WebhookRequest) {
    switch req.Type {
    case "2", "23": // 사진, 앨범
        if req.Attachment != "" {
            // /query 로 복호화 후 URL 추출
        }
    case "26": // 답장
        // 인용 메시지 처리
    default:
        // 텍스트 명령어 처리
    }
}
```

### 주의사항

- `Type`이 빈 문자열인 경우: Iris가 해당 메시지의 type을 읽지 못한 것. 텍스트로 간주해도 무방.
- `Attachment`는 암호화 상태. JSON 파싱 시도하면 실패. 반드시 복호화 후 사용.
- `omitempty` 태그 필수 — Iris가 값이 없을 때 필드를 생략하므로, 클라이언트도 부재를 허용해야 함.
