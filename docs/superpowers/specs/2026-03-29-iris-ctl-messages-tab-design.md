# iris-ctl Messages Tab Design (Draft)

## Overview

iris-ctl TUI에 Messages 탭을 추가하여, 방의 채팅 메시지 히스토리를 조회하고 해당 맥락에서 바로 Reply를 보낼 수 있게 한다.
현재 Reply Modal은 대화 맥락 없이 방 ID만으로 전송하는 구조여서, 메시지를 보면서 답장하는 자연스러운 UX가 불가능하다.

## Motivation

- Reply Modal은 기능적으로 완성되었으나, 대화 흐름을 보지 못한 채 메시지를 보내야 함
- 스레드 선택도 origin message 미리보기만 가능하고, 스레드 내 대화 흐름은 볼 수 없음
- Messages 탭이 생기면 기존 Reply Modal을 그대로 재사용하면서 컨텍스트만 자동 연결

## Scope

### In scope

- Messages 탭: 5번째 탭으로 추가 (`[Rooms] [Members] [Stats] [Messages] [Events]`)
- 방의 최근 메시지 조회 (기존 `POST /query` API 활용)
- 메시지 복호화 표시 (`decrypt=true` 파라미터)
- 스크롤 (위/아래 방향키, PageUp/PageDown)
- 스레드 메시지 접기/펼치기
- 메시지 선택 후 `r` → Reply Modal에 방 + 스레드 ID 자동 채움
- 자동 갱신 (폴링 주기에 맞춰 새 메시지 추가)

### Out of scope (향후 이터레이션)

- 실시간 SSE 기반 메시지 스트리밍
- 메시지 검색
- 이미지/파일 첨부 미리보기
- 메시지 삭제/수정
- 무한 스크롤 (초기에는 최근 N건 고정)

## Proposed Layout

```
[Rooms] [Members] [Stats] [Messages] [Events]
┌─ Messages (#방이름) ─────────────────────────────────────┐
│  [03-29 12:01] alice: 안녕하세요                           │
│  [03-29 12:02] bob: ㅎㅇ                                  │
│  [03-29 12:03] alice: 이거 어떻게 해요?                    │
│    └─ 스레드 (3건) ▸                          ← Enter 펼치기│
│  [03-29 12:05] charlie: 공지 확인해주세요                   │
│  [03-29 12:06] dave: 확인했습니다                          │
│▶ [03-29 12:08] alice: 감사합니다                ← 현재 선택 │
│                                                           │
├───────────────────────────────────────────────────────────┤
│ ↑↓ 이동 │ Enter 스레드 │ r 답장 │ / 검색 │ q 닫기          │
└───────────────────────────────────────────────────────────┘
```

### 스레드 펼침 상태

```
│  [03-29 12:03] alice: 이거 어떻게 해요?                    │
│    └─ 스레드 (3건) ▾                                       │
│       [12:04] bob: 설정에서 바꾸면 됩니다                    │
│       [12:05] alice: 감사합니다                             │
│       [12:06] charlie: 저도 궁금했어요                      │
│  [03-29 12:05] charlie: 공지 확인해주세요                   │
```

## Data Flow

### 메시지 조회

기존 `POST /query` API를 활용한다:

```sql
SELECT id, user_id, message, type, created_at, thread_id, v
FROM chat_logs
WHERE chat_id = ?
ORDER BY created_at DESC
LIMIT 50
```

- `decrypt=true` 로 서버 측 복호화 적용
- 닉네임 해석: `GET /rooms/{chatId}/members`의 캐시된 결과 활용

### 전용 API 추가 여부

| 방식 | 장점 | 단점 |
|------|------|------|
| `POST /query` 재활용 | 서버 변경 없음 | SQL 직접 작성, 구조화 안 됨 |
| `GET /rooms/{chatId}/messages` 신규 | 구조화된 응답, 페이지네이션 | 서버 엔드포인트 추가 필요 |

**추천: `POST /query` 재활용으로 시작**, 이후 필요 시 전용 API로 전환.

### Reply 연동

메시지 선택 후 `r`:
1. 선택된 메시지의 `chat_id` → Reply Modal의 Room 자동 채움
2. 선택된 메시지가 스레드에 속하면 → `thread_id` 자동 채움
3. 선택된 메시지 자체가 스레드 시작이면 → 해당 `id`를 `thread_id`로
4. 스레드 없는 일반 메시지 → 스레드 없이 방에 전송

## State Structure (초안)

```rust
pub struct MessagesView {
    chat_id: Option<i64>,
    messages: Vec<ChatMessage>,
    scroll_offset: usize,
    selected_index: usize,

    // 스레드 펼침 상태
    expanded_threads: HashSet<i64>,

    // 닉네임 캐시 (members API에서)
    nicknames: HashMap<i64, String>,
}

struct ChatMessage {
    id: i64,
    user_id: i64,
    message: String,       // 복호화 후
    msg_type: i32,
    created_at: i64,
    thread_id: Option<i64>,
}
```

## Key Mapping

| 키 | 동작 |
|----|------|
| ↑ / ↓ | 메시지 선택 이동 |
| PageUp / PageDown | 페이지 단위 스크롤 |
| Enter | 스레드 접기/펼치기 |
| r | 선택된 메시지 컨텍스트로 Reply Modal 오픈 |
| Home / End | 최신/최구 메시지로 이동 |

## Dependencies

- 기존 `POST /query` API (변경 없음)
- 기존 `GET /rooms/{chatId}/members` API (닉네임 해석)
- Reply Modal (`views/reply_modal.rs`) — 이미 구현됨, 재사용

## File Impact (예상)

### New files
- `tools/iris-ctl/src/views/messages.rs` — MessagesView 구조체, 렌더링, 키 핸들링

### Modified files
- `tools/iris-ctl/src/views/mod.rs` — messages 모듈 선언, TabId에 Messages 추가
- `tools/iris-ctl/src/app.rs` — Messages 탭 렌더링/키 라우팅, 'r' 컨텍스트 확장
- `tools/iris-ctl/src/main.rs` — 메시지 폴링 로직 추가
- `tools/iris-common/src/api.rs` — (선택) 전용 messages API 추가 시

## Open Questions

1. **페이지네이션**: 초기 50건 이후 이전 메시지를 어떻게 로드할 것인가? (위로 스크롤 시 추가 로드?)
2. **메시지 타입 표시**: 이미지/파일/이모티콘 등 텍스트가 아닌 메시지를 어떻게 표시할 것인가?
3. **폴링 주기**: 기존 room 데이터와 동일한 주기로 갱신? 더 짧은 주기가 필요한가?
4. **방 전환**: Rooms 탭에서 방 선택 시 자동으로 Messages 탭으로 전환할 것인가?
5. **POST /query vs 전용 API**: 초기에 query 재활용 후 언제 전용 API로 전환할 것인가?

## Status

Draft — 브레인스토밍 및 상세 설계 필요. Reply Modal 구현 완료 후 다음 이터레이션으로 진행.
