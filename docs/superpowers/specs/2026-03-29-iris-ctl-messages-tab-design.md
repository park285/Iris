# iris-ctl Messages Tab Design

## Overview

`iris-ctl` TUI에 `Messages` 탭을 추가한다. 사용자는 선택된 방의 최근 메시지 50건을 읽고, 해당 메시지의 맥락을 유지한 채 `ReplyModal`을 열어 바로 답장할 수 있다.

이 작업의 목표는 새 reply 시스템을 만드는 것이 아니라, **기존 Reply Modal에 메시지 컨텍스트를 안정적으로 공급하는 읽기 전용 메시지 뷰를 추가하는 것**이다.

## Goals

- TUI에 5번째 탭으로 `Messages` 추가
- 현재 선택된 방의 최근 메시지 50건 표시
- `POST /query` 재활용으로 서버 변경 없이 구현 시작
- 스레드 접기/펼치기 지원
- 선택 메시지 기준으로 `ReplyModal`에 room/thread context 사전 입력
- 기존 poll 주기에 맞춘 자동 갱신

## Non-Goals

- 전용 messages API 추가
- SSE 기반 실시간 메시지 스트리밍
- 메시지 검색
- 이미지/파일 미리보기
- 무한 스크롤 또는 추가 페이지 로드
- 메시지 수정/삭제
- Rooms 탭에서 Messages 탭으로의 자동 전환

## Final Decisions

### Data source
- 기존 `POST /query`를 재활용한다.
- `decrypt=true`를 사용해 서버 측 복호화 결과를 표시한다.

### Initial message window
- 최근 50건 고정으로 시작한다.
- 추가 페이지 로드나 무한 스크롤은 넣지 않는다.

### Non-text message rendering
- 첫 버전에서는 placeholder만 표시한다.
- 예: `[image]`, `[file]`, `[emoticon]`, `[unsupported]`

### Room-to-Messages navigation
- Rooms 탭에서 방 선택 시 Messages 탭으로 자동 전환하지 않는다.
- 방 컨텍스트만 갱신하고, 사용자가 직접 Messages 탭으로 이동한다.

### Architecture choice
- `MessagesView`를 별도 파일로 두는 **뷰 독립형 구조**를 채택한다.
- `app.rs`는 탭 전환과 액션 라우팅을 담당하고, 메시지 상태와 렌더링은 `views/messages.rs`에 모은다.

## Proposed Layout

```text
[Rooms] [Members] [Stats] [Messages] [Events]
┌─ Messages (#room-name) ────────────────────────────────────┐
│  [03-29 12:01] alice: 안녕하세요                           │
│  [03-29 12:02] bob: ㅎㅇ                                  │
│  [03-29 12:03] alice: 이거 어떻게 해요?                    │
│    └─ thread (3) ▸                                        │
│  [03-29 12:05] charlie: 공지 확인해주세요                   │
│▶ [03-29 12:08] alice: 감사합니다                           │
│                                                           │
├───────────────────────────────────────────────────────────┤
│ ↑↓ move │ Enter thread │ r reply │ PgUp/PgDn page │ q quit │
└───────────────────────────────────────────────────────────┘
```

Thread expanded:

```text
│  [03-29 12:03] alice: 이거 어떻게 해요?                    │
│    └─ thread (3) ▾                                        │
│       [12:04] bob: 설정에서 바꾸면 됩니다                  │
│       [12:05] alice: 감사합니다                            │
│       [12:06] charlie: 저도 궁금했어요                     │
```

## Architecture

### Responsibilities

#### `tools/iris-ctl/src/views/messages.rs`
Owns:
- selected room context for messages view
- in-memory message list
- thread expansion state
- selection and scroll state
- flattened rows for rendering
- reply context derivation from current selection
- empty/loading/error rendering for the messages pane

#### `tools/iris-ctl/src/app.rs`
Owns:
- adding `Messages` to the tab model
- routing render/key handling to `MessagesView`
- opening `ReplyModal` using message-derived context
- sharing selected room context across tabs

#### `tools/iris-ctl/src/main.rs`
Owns:
- polling and refreshing message data for the selected room
- adapting query results into `MessagesView` input
- reusing already-fetched member data for nickname mapping

This keeps the split as:
- UI state in `MessagesView`
- tab orchestration in `app.rs`
- network refresh in `main.rs`

## Data Model

### `MessagesView`

```rust
pub struct MessagesView {
    pub chat_id: Option<i64>,
    messages: Vec<ChatMessage>,
    selected_index: usize,
    scroll_offset: usize,
    expanded_threads: HashSet<i64>,
    nicknames: HashMap<i64, String>,
}
```

### `ChatMessage`

```rust
struct ChatMessage {
    id: i64,
    chat_id: i64,
    user_id: i64,
    message: String,
    msg_type: i32,
    created_at: i64,
    thread_id: Option<i64>,
}
```

### Flattened render rows

`MessagesView` stores canonical messages, then computes flattened rows during rendering/key handling.

Row kinds:
- top-level message row
- thread header row
- expanded thread child row

This allows thread expansion to remain a view concern instead of mutating the source message list.

## Data Flow

### 1. Room context source

`Messages` does not own room selection. It reads the current room context already selected elsewhere in the app.

v1 behavior:
- room selection updates shared room context
- no automatic tab switch to `Messages`
- user moves to `Messages` explicitly

### 2. Message fetch

Use `POST /query` with server-side decryption.

Reference query shape:

```sql
SELECT id, chat_id, user_id, message, type, created_at, thread_id, v
FROM chat_logs
WHERE chat_id = ?
ORDER BY created_at DESC
LIMIT 50
```

Behavior:
- fetch most recent 50 messages in descending order
- reverse to oldest → newest for display readability
- preserve existing list when refresh fails
- refresh on the same poll cadence already used by `iris-ctl`

### 3. Nickname resolution

Do not add a new members fetch for `Messages`.

Instead:
- reuse the selected room's existing members response
- derive `user_id -> nickname` map from that data
- fall back to `user_id` when no nickname is available

### 4. Thread grouping

For v1, thread display should prefer robustness over perfect reconstruction.

Rules:
- `thread_id == None` → normal top-level message
- messages sharing a thread id are grouped together
- when a stable root is identifiable, use it as the header context
- when root identification is ambiguous, treat the first displayed message in that thread group as the visible thread anchor

If grouping becomes ambiguous or partial, the UI may safely degrade to flatter rendering rather than producing incorrect reply context.

### 5. Reply integration

`MessagesView` computes reply context from the selected row.

Rules:
- selected child message → use its `thread_id`
- selected thread root/header → use the root thread id
- selected non-thread message → open reply without thread id

The selected room is always prefilled.
The thread id is prefilled only when applicable.
The existing `ReplyModal` remains the sending UI.

## Interaction Design

### Key mapping

| Key | Action |
|---|---|
| `↑` / `↓` | move selection |
| `PageUp` / `PageDown` | move by page |
| `Home` / `End` | jump to first/last visible row |
| `Enter` | toggle thread expansion when on a thread row |
| `r` | open `ReplyModal` with current message context |
| `Tab` / `Shift+Tab` | existing tab navigation |
| `q` | quit app |

### Empty states

No selected room:
- `No room selected. Choose a room in Rooms tab.`

No messages:
- `No messages`

### Visual behavior

- title format: `Messages (<room name>)`
- thread children render one indentation level deeper than parent rows
- selection operates on visible rows, not raw source indices

## Error Handling

v1 uses status-line driven error handling and keeps the last good state when possible.

### Fetch failure
- keep previously rendered messages visible
- update the status line with a concise failure message
- do not clear the pane just because one poll failed

### Selected room disappears
- clear `chat_id`
- clear message list
- show empty-state guidance

### Missing nickname
- show `user_id` fallback

### Ambiguous thread structure
- prefer safe flat rendering over incorrect grouping
- only prefill reply thread context when confidence is sufficient

## File Impact

### New file
- `tools/iris-ctl/src/views/messages.rs` — messages state, flattening, rendering, key handling, reply context derivation

### Modified files
- `tools/iris-ctl/src/views/mod.rs` — `messages` module, `TabId::Messages`, view actions as needed
- `tools/iris-ctl/src/app.rs` — render/key routing, shared room binding, reply open from messages context
- `tools/iris-ctl/src/main.rs` — poll refresh for messages, query result adaptation, nickname map update
- `tools/iris-ctl/src/api.rs` — optional thin helper if query invocation needs a TUI-local wrapper

## Testing Strategy

### Unit tests for `MessagesView`
- flatten top-level messages into visible rows
- expand/collapse thread rows
- preserve valid selection while rows change
- derive correct reply context for plain message, thread header, and thread child
- render placeholder labels for non-text message types

### App integration tests
- include `Messages` in tab ordering
- route render/key handling to `MessagesView`
- `r` on `Messages` opens `ReplyModal` with prefilled room/thread context

### Adapter tests
- query result → `ChatMessage` conversion
- descending query order → ascending display order normalization
- member list → nickname map derivation

## Verification

Minimum verification for this work:

```bash
cargo test --manifest-path tools/iris-ctl/Cargo.toml
cargo clippy --manifest-path tools/iris-ctl/Cargo.toml --all-targets -- -D warnings
```

## Implementation Notes

- Prefer reusing existing view patterns from `rooms.rs`, `members.rs`, and `stats.rs`
- Keep `app.rs` from absorbing messages-specific list logic
- Avoid introducing a new public API unless `POST /query` proves insufficient in a later iteration
- Keep v1 scope focused on read-context + reply handoff
