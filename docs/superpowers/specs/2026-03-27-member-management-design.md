# Iris Member Management — Design Spec

**Date:** 2026-03-27
**Status:** Approved

## Overview

Iris를 확장하여 오픈채팅방의 유저/멤버를 조회, 모니터링, 분석할 수 있는 기능을 추가한다.
DB 기반 조회 API, Bridge reflection 탐색, 이벤트 감지, 활동 분석, Rust TUI 도구를 포함한다.

## Validated Assumptions

실 인스턴스(KR, Redroid)에서 검증 완료:

| 항목 | 결과 |
|---|---|
| `open_chat_member` 테이블 구조 | `user_id`, `nickname`(enc), `link_member_type`(1=방장/2=일반/4=부방장/8=봇), `privilege`, `profile_image_url`, `report`, `enc` |
| `chat_rooms` 테이블 구조 | `members`(JSON 배열), `active_member_ids`, `active_members_count`, `blinded_member_ids`, `link_id`, `type`, `meta`(공지 JSON) |
| `open_link` 테이블 | `name`, `url`, `member_limit`, `searchable`, `description` |
| `open_profile` 테이블 | 봇 자신의 프로필 — `link_member_type`, `nickname` |
| `openchat_bot_command` 테이블 | 네이티브 봇 명령어 (`name`, `bot_id`, `link_id`) |
| 닉네임 복호화 | `KakaoDecrypt.decrypt(enc=31, ciphertext, botId)` — botId 기반 키로 정상 동작 확인 |
| 시스템 이벤트 저장 여부 | 입장/퇴장/강퇴 이벤트는 `chat_logs`에 저장되지 않음 (전 타입 복호화 확인) |
| 봇 역할 | link 455150406에서 방장(1), link 31295535에서 일반(2) |
| 메시지 타입 매핑 | 0=text, 1=photo, 2=video, 3=voice, 12=file, 20=emoticon, 26=reply, 27=multi-photo, 71/16455=hashtag-search, 16385=url-share, 16386=media |

## Pending Validation (post-deploy)

| 항목 | 검증 방법 |
|---|---|
| `chat_rooms.members` 배열 실시간 갱신 | 입퇴장 발생 시 diff 관찰 |
| `chat_rooms.blinded_member_ids` 강퇴 시 갱신 | 강퇴 이벤트 발생 시 관찰 |
| Bridge ChatRoom reflection | `/diagnostics/chatroom-fields` 엔드포인트로 탐색 |

## Common Constraints

- 모든 API 응답은 `KakaoDecrypt.decrypt()` 경유 복호화된 평문만 반환
- 기존 인증 동일 (`X-Bot-Token` 헤더)
- 기존 폴링 사이클(`pollChatLogsAfter()`) 재사용, 별도 폴링 루프 없음
- 에러 응답 형식 통일: `{"error": "<message>", "code": "<ERROR_CODE>"}` — 모든 4xx/5xx 응답에 적용

## Phase 1 — API Endpoints (DB-based)

### `GET /rooms`

참여 중인 오픈채팅방 목록.

```json
{
  "rooms": [
    {
      "chatId": 18219201472247343,
      "type": "OM",
      "linkId": 31295535,
      "activeMembersCount": 97,
      "linkName": "팩린이질문방",
      "linkUrl": "https://open.kakao.com/o/gl7xW8I",
      "memberLimit": null,
      "searchable": 0,
      "botRole": 2
    }
  ]
}
```

Data sources: `chat_rooms` JOIN `open_link` JOIN `open_profile`

### `GET /rooms/{chatId}/members`

해당 방의 멤버 목록 + 역할.

```json
{
  "chatId": 18219201472247343,
  "linkId": 31295535,
  "members": [
    {
      "userId": 6713050839858222494,
      "nickname": "닉네임",
      "role": "owner",
      "roleCode": 1,
      "profileImageUrl": "..."
    }
  ],
  "totalCount": 97
}
```

`roleCode` mapping: `1→"owner"`, `2→"member"`, `4→"admin"`, `8→"bot"`

현재 최대 방 인원 97명 수준이므로 전량 반환. pagination 미적용은 의도적 — 오픈채팅 인원 상한(최대 1,500명)에서도 단일 응답이 허용 가능한 크기이므로 YAGNI 적용. 향후 필요 시 `?limit=&offset=` 추가 가능.

Data sources: `open_chat_member` WHERE `link_id` matches, nicknames decrypted with botId

### `GET /rooms/{chatId}/info`

방 상세정보.

```json
{
  "chatId": 18219201472247343,
  "type": "OM",
  "linkId": 31295535,
  "notices": [
    { "content": "공지 텍스트...", "authorId": 6713050839858222494, "updatedAt": 1687503303 }
  ],
  "blindedMemberIds": [],
  "botCommands": [
    { "name": "분위기", "botId": 7399041588235753630 }
  ],
  "openLink": {
    "name": "팩린이질문방",
    "url": "https://open.kakao.com/o/gl7xW8I",
    "memberLimit": null,
    "description": "#팩토리오 #공돌이 ...",
    "searchable": 0
  }
}
```

Data sources: `chat_rooms.meta` (JSON parse, type=1 for notices), `openchat_bot_command`, `open_link`

### `RoutingCommand` Extension

기존 webhook payload에 sender 역할 추가:

```kotlin
val senderRole: Int? = null  // 1=owner, 2=member, 4=admin, 8=bot, null=unknown
```

메시지 수신 시 `open_chat_member` 에서 sender의 `link_member_type` 조회.
조회 실패 시(sender가 `open_chat_member`에 없는 경우, 예: 일반 그룹채팅, DM 등) `senderRole = null`로 fallback.

## Phase 2 — Bridge Reflection Scan

### ChatRoomIntrospector

`ChatRoomResolver`가 이미 호출하는 `broadRoomResolverMethod(roomId, includeMembers=true, includeOpenLink=true)` 반환 객체를 reflection으로 스캔.

```
broadRoomResolverMethod 반환 ChatRoom 객체
    ↓
ChatRoomIntrospector.scan()
    ├── 모든 필드 순회 (이름, 타입, 값 스냅샷)
    ├── Collection → size + elementType만
    └── 중첩 객체 → depth=1까지만 재귀
    ↓
결과 캐싱 (roomId → FieldMap)
```

### Diagnostic Endpoint

`GET /diagnostics/chatroom-fields/{chatId}`

```json
{
  "chatId": 18219201472247343,
  "className": "hp.A0",
  "scannedAt": 1711545600,
  "fields": [
    { "name": "a", "type": "long", "value": 18219201472247343 },
    { "name": "b", "type": "java.lang.String", "value": "팩린이질문방" },
    { "name": "c", "type": "java.util.List", "size": 97, "elementType": "hp.B2" }
  ]
}
```

### Safety

- 필드 읽기만, setter/메서드 호출 없음
- 진단 엔드포인트 호출 시에만 스캔 (자동 실행 안 함)
- 중첩 depth=1 제한 — 순환 참조 방지 및 KakaoTalk 프로세스 내 reflection 부하 최소화 목적

### File Location

```
bridge/src/main/java/party/qwer/iris/bridge/
└── ChatRoomIntrospector.kt    (new)
```

## Phase 3 — Runtime Data Integration

Phase 2에서 발견된 유의미한 런타임 필드를 Phase 1 엔드포인트에 통합.

### Response Extension

Phase 1 응답에 선택적 `runtime` 블록 추가. 런타임 데이터 없으면 블록 생략.

```json
{
  "userId": 6713050839858222494,
  "nickname": "닉네임",
  "role": "owner",
  "runtime": {
    "discoveredField": "value"
  }
}
```

### Field Mapping Registry

```kotlin
object ChatRoomFieldMapping {
    val knownFields: Map<String, String>  // obfuscated name → semantic name
}
```

Phase 2 discovery 후 유의미한 필드를 등록. 등록된 필드만 `runtime` 블록에 포함.

난독화 이름은 KakaoTalk 앱 업데이트 시 변경될 수 있으므로, 매핑된 필드가 실제 객체에서 발견되지 않으면 경고 로그를 출력하고 해당 필드를 `runtime` 블록에서 제외한다. `/diagnostics/chatroom-fields` 엔드포인트로 새 필드명을 확인한 뒤 매핑을 갱신하는 절차를 따른다.

### Bridge ↔ App Communication

```
IrisServer                          Bridge (KakaoTalk process)
  GET /rooms/{id}/members
  ── DB query ──→
  ── UDS request ──→                broadResolve + introspect
  ←── runtime fields JSON ──
  ── merge and respond ──→
```

### Failure Isolation

| Scenario | Behavior |
|---|---|
| Bridge disconnected | `runtime` block omitted, DB-only 200 OK |
| Reflection failure | Same — DB fallback, error logged |
| No field mappings | `runtime: {}` empty object |

Runtime data is always bonus — API contract holds without it.

## Phase 4 — Event Detection (polling piggyback)

별도 폴링 루프 없이 기존 `pollChatLogsAfter()` 사이클에 snapshot diff 연결.

### Operation

```
existing pollChatLogsAfter() cycle
    ├── new message processing (existing behavior)
    └── snapshot diff (added)
          ├── chat_rooms.members diff        → join/leave
          ├── chat_rooms.blinded_member_ids  → kick classification
          ├── open_chat_member nickname      → nickname_change
          ├── open_chat_member role          → role_change
          └── open_chat_member profile_image → profile_change
          ↓
        changes detected → webhook dispatch
```

### Snapshot Structure

```kotlin
data class RoomSnapshot(
    val memberIds: Set<Long>,
    val blindedIds: Set<Long>,
    val nicknames: Map<Long, String>,       // userId → decrypted nickname
    val roles: Map<Long, Int>,              // userId → link_member_type
    val profileImages: Map<Long, String>,   // userId → profile_image_url
)
```

### Event Classification

| Diff Result | Blinded Cross-check | Final Event |
|---|---|---|
| userId added | — | `join` |
| userId removed | added to blinded | `kick` (confirmed) |
| userId removed | not in blinded | `leave` (estimated, `"estimated": true`) |
| nickname changed | — | `nickname_change` |
| role changed | — | `role_change` |
| profile image changed | — | `profile_change` |

### Webhook Payloads

```json
{
  "type": "member_event",
  "event": "join",
  "chatId": 18219201472247343,
  "linkId": 31295535,
  "userId": 1234567890,
  "nickname": "새멤버",
  "estimated": false,
  "timestamp": 1711545600
}
```

```json
{
  "type": "nickname_change",
  "chatId": 18219201472247343,
  "linkId": 31295535,
  "userId": 1234567890,
  "oldNickname": "이전닉",
  "newNickname": "변경닉",
  "timestamp": 1711545600
}
```

Event types: `join`, `leave`, `kick`, `nickname_change`, `role_change`, `profile_change`

### SSE Event Stream

`GET /events/stream` — Server-Sent Events 엔드포인트. Phase 6 TUI 및 외부 클라이언트가 실시간 구독.

```
GET /events/stream
Accept: text/event-stream
X-Bot-Token: ...

data: {"type":"member_event","event":"join","chatId":18219201472247343,"linkId":31295535,"userId":1234567890,"nickname":"새멤버","estimated":false,"timestamp":1711545600}

data: {"type":"nickname_change","chatId":18219201472247343,"linkId":31295535,"userId":1234567890,"oldNickname":"이전닉","newNickname":"변경닉","timestamp":1711545600}
```

Design principles (per h2c optimization memory):
- **h2c webhook** = operational path (reliability, retries) — unchanged
- **SSE** = observation path (real-time, fan-out) — new
- 같은 이벤트를 webhook과 SSE에 병렬 fan-out
- SSE 클라이언트 부재 시에도 webhook 정상 동작 (backpressure isolation)
- SSE 채널은 운영 경로에 backpressure를 전달하지 않음

Implementation: Ktor `respondSse` / `respondBytesWriter` with `text/event-stream` content type.

SSE reconnection policy:
- 각 이벤트에 `id` 필드 포함 (monotonic counter)
- 클라이언트 재연결 시 `Last-Event-ID` 헤더 지원 — 최근 N개(기본 100) 이벤트 인메모리 링버퍼에서 replay
- 링버퍼 범위를 초과한 이벤트는 복구 불가 — observation path이므로 replay 보장 없음, 확실한 상태는 REST API 조회로 확인

### Known Limitations

| Limitation | Reason |
|---|---|
| kick vs leave: blinded presence only | No event type stored in DB |
| Fast join+leave within poll interval missed | Diff cannot capture intra-cycle changes |
| DB sync delay | Depends on KakaoTalk app's server→local sync timing |

## Phase 5 — Member Activity Analysis

### `GET /rooms/{chatId}/stats?period=7d&limit=20`

```json
{
  "chatId": 18219201472247343,
  "period": { "from": 1711000000, "to": 1711545600 },
  "totalMessages": 7829,
  "activeMembers": 45,
  "topMembers": [
    {
      "userId": 5613942128875002916,
      "nickname": "닉네임A",
      "messageCount": 312,
      "lastActiveAt": 1711545500,
      "messageTypes": { "text": 280, "photo": 25, "video": 7 }
    }
  ]
}
```

Query params: `period` (7d/30d/all), `limit` (top N), `minMessages` (filter)

### `GET /rooms/{chatId}/members/{userId}/activity?period=30d`

```json
{
  "userId": 5613942128875002916,
  "nickname": "닉네임A",
  "messageCount": 312,
  "firstMessageAt": 1700000000,
  "lastMessageAt": 1711545500,
  "activeHours": [0,0,1,0,0,0,3,12,25,31,28,19,15,22,30,35,28,20,18,12,8,3,1,1],
  "messageTypes": { "text": 280, "photo": 25, "video": 7 }
}
```

`activeHours`: 24-element array, message count per hour of day.

Data source: `chat_logs` GROUP BY `user_id`, filtered by `chat_id` and `created_at` range.

Performance: `chat_logs` 테이블의 `chat_id` 인덱스를 활용. `period` 파라미터로 스캔 범위를 제한하고, 쿼리에 row limit(기본 50,000)을 적용하여 대용량 방에서도 DB 부하를 제어한다.

## Phase 6 — TUI Tool (`iris-ctl`)

### Stack

- **Language:** Rust
- **TUI framework:** `ratatui` (v0.30+) with `crossterm` backend
- **HTTP client:** `reqwest` (async, json feature)
- **Async runtime:** `tokio`
- **Serialization:** `serde` + `serde_json`

### App Structure

```rust
// ratatui immediate-mode rendering loop
fn main() -> std::io::Result<()> {
    ratatui::run(|mut terminal| {
        let mut app = App::new(config);
        loop {
            terminal.draw(|frame| app.render(frame))?;
            if app.handle_event(event::read()?)? {
                break Ok(());
            }
        }
    })
}
```

### Views

| Tab | Content | Key Bindings |
|---|---|---|
| **Rooms** | 방 목록 테이블 (이름, 타입, 인원, 봇역할) | `Enter`=멤버, `s`=통계, `i`=상세 |
| **Members** | 멤버 테이블 (닉네임, 역할, 메시지수, 마지막활동) | `/`=검색, `r`=역할필터, `a`=활동순, `n`=이름순 |
| **Stats** | 활동 통계 (시간대 BarChart, 메시지 타입 분포) | `7`/`30`/`a`=기간 전환 |
| **Events** | 실시간 이벤트 스트림 via SSE 구독 (입장/퇴장/닉변/역할변경/프로필변경) | `f`=필터, `p`=일시정지, `c`=클리어 |

### Widgets Used

- `Table` + `TableState`: Rooms, Members 목록 (stateful selection, scrolling)
- `Tabs`: 상단 뷰 전환
- `BarChart`: 시간대별 활동 분포
- `Paragraph`: 상세정보, 공지사항
- `List` + `ListState`: 이벤트 스트림

### Configuration

```toml
# ~/.config/iris-ctl/config.toml (file permission: 600)
[server]
url = "http://100.100.1.4:PORT"
token = "X-Bot-Token-value"  # 환경변수 IRIS_TOKEN으로 오버라이드 가능

[ui]
poll_interval_secs = 5
```

Token resolution order: `IRIS_TOKEN` env var → config.toml `token` field. Config 파일에는 `chmod 600` 권한을 요구하며, 그 외 권한이면 경고 출력.

### Build & Distribution

```bash
# native build
cargo build --release

# cross-compile for KR server (aarch64)
cross build --release --target aarch64-unknown-linux-gnu
```

Single binary, no runtime dependencies.

### File Location

```
tools/iris-ctl/
├── Cargo.toml
├── src/
│   ├── main.rs          # entry point, terminal setup
│   ├── app.rs           # App state, view routing
│   ├── api.rs           # reqwest HTTP client wrapper
│   ├── views/
│   │   ├── rooms.rs     # Rooms tab
│   │   ├── members.rs   # Members tab
│   │   ├── stats.rs     # Stats tab
│   │   └── events.rs    # Events tab
│   └── models.rs        # API response types (serde)
└── README.md
```

## Phase Dependencies

```
Phase 1 (API)  ─────────┐
Phase 2 (Reflection) ───┤──→ Phase 3 (Integration)
Phase 4 (Events) ────────┘
Phase 5 (Stats) ──────────── (independent)
Phase 6 (TUI) ───────────── (depends on Phase 1 API availability)
```

Phase 1, 2, 4, 5 are parallelizable. Phase 3 requires 1+2. Phase 6 requires Phase 1 endpoints + Phase 4 SSE stream for Events tab.

## DB Schema Reference

### chat_rooms (KakaoTalk.db)

```sql
CREATE TABLE `chat_rooms` (
  `_id` INTEGER PRIMARY KEY AUTOINCREMENT,
  `id` INTEGER NOT NULL,
  `type` TEXT,
  `members` TEXT,
  `active_member_ids` TEXT,
  `last_log_id` INTEGER,
  `last_message` TEXT,
  `last_updated_at` INTEGER,
  `unread_count` INTEGER,
  `watermarks` TEXT,
  `temporary_message` TEXT,
  `v` TEXT,
  `ext` TEXT,
  `last_read_log_id` INTEGER,
  `last_update_seen_id` INTEGER,
  `active_members_count` INTEGER,
  `meta` TEXT,
  `is_hint` INTEGER,
  `private_meta` TEXT,
  `last_chat_log_type` INTEGER,
  `schat_token` INTEGER,
  `last_skey_token` INTEGER,
  `last_pk_tokens` TEXT,
  `link_id` INTEGER,
  `moim_meta` TEXT,
  `invite_info` TEXT,
  `blinded_member_ids` TEXT,
  `mute_until_at` INTEGER,
  `last_joined_log_id` INTEGER
);
```

### open_chat_member (KakaoTalk2.db)

```sql
CREATE TABLE `open_chat_member` (
  `_id` INTEGER PRIMARY KEY AUTOINCREMENT,
  `link_id` INTEGER NOT NULL,
  `user_id` INTEGER NOT NULL,
  `type` INTEGER NOT NULL,
  `profile_type` INTEGER,
  `link_member_type` INTEGER NOT NULL,
  `nickname` TEXT,
  `profile_image_url` TEXT,
  `full_profile_image_url` TEXT,
  `original_profile_image_url` TEXT,
  `involved_chat_id` INTEGER NOT NULL,
  `profile_link_id` INTEGER,
  `privilege` INTEGER,
  `report` INTEGER NOT NULL,
  `enc` INTEGER NOT NULL
);
```

### open_link (KakaoTalk2.db)

```sql
CREATE TABLE `open_link` (
  `id` INTEGER,
  `user_id` INTEGER NOT NULL,
  `token` INTEGER,
  `name` TEXT,
  `url` TEXT,
  `image_url` TEXT,
  `type` INTEGER,
  `member_limit` INTEGER,
  `direct_chat_limit` INTEGER,
  `active` INTEGER,
  `expired` INTEGER,
  `created_at` INTEGER,
  `view_type` INTEGER,
  `push_alert` INTEGER,
  `icon_url` TEXT,
  `v` TEXT,
  `searchable` INTEGER,
  `description` TEXT,
  PRIMARY KEY(`id`)
);
```

## Message Type Reference

| Type | Meaning | Count (sampled) |
|---|---|---|
| 0 | Text | 31 |
| 1 | Photo | 7235 |
| 2 | Video | 326 |
| 3 | Voice | 9 |
| 12 | File | 31 |
| 20 | Emoticon | 23 |
| 26 | Reply/Quote | 76 |
| 27 | Multi-photo | 7 |
| 71 | Hashtag search | 68 |
| 98 | Rich card | 3 |
| 16385 | URL share | 10 |
| 16386 | Media | 9 |
| 16455 | Hashtag search (variant) | 1 |
