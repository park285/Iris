# iris-ctl Reply Modal Design

## Overview

iris-ctl TUI에 컨텍스트 연동 Reply 모달을 추가하여, `/reply` 엔드포인트를 TUI에서 직접 사용할 수 있게 한다.
현재 iris-ctl은 읽기 전용(Rooms/Members/Stats/Events)이며, 쓰기 요청 기능이 없다.

## Scope

### In scope

- Reply 모달: text, image, image_multiple, markdown 전체 타입
- 방 선택: 현재 탭 컨텍스트에서 자동 채움 + 방 선택기
- 스레드 지정: 기존 스레드 답장과 새 스레드 열기를 동일한 인터페이스로 통합
- 서버 측 `GET /rooms/{chatId}/threads` 신규 엔드포인트
- 클라이언트 측 `IrisApi` 확장 (`send_reply`, `list_threads`)
- 비동기 전송 + 결과 인라인 표시

### Out of scope

- 마크다운 프리뷰 렌더링
- Reply 상태 추적 (`/reply-status` 폴링)
- 이미지 클립보드 붙여넣기
- 파일 브라우저 위젯

## Verified Premises

| 전제 | 검증 결과 |
|------|----------|
| Tailscale 직접 API 접근 | IRIS_BIND_HOST=0.0.0.0, health OK |
| HMAC 인증 동작 | GET /rooms 200, POST /query 200 |
| chat_logs.thread_id 컬럼 | thread_id INTEGER 확인 |
| thread 데이터 집계 가능 | GROUP BY thread_id, chat_id 정상 동작, 활성 데이터 존재 |
| chat_logs.scope 컬럼 | scope INTEGER 확인 |

## Section 1: Modal Structure and Layout

### Entry Point

어느 탭에서든 `r` 키로 모달 진입. 현재 선택된 방이 자동으로 Room 필드에 채워진다.
방 컨텍스트가 없는 탭(Events)에서는 방 선택기부터 표시.

### Layout

```
+-- Reply ------------------------------------------------+
|                                                          |
|  Type    [* text  o image  o images  o markdown]         |
|  Room    [방이름 (12345678)]                     [> 선택] |
|  Thread  [없음 / 지정]                           [> 선택] |
|  Scope   [o room  * thread  o both]    <- Thread 활성 시  |
|                                                          |
|  +- Content -------------------------------------------+ |
|  | 메시지를 입력하세요...                                 | |
|  |                                                      | |
|  |                                                      | |
|  +------------------------------------------------------+ |
|                                                          |
|  Ctrl+S 전송 | Tab 다음 | Esc 취소                       |
|  (결과 인라인 표시 영역)                                   |
+----------------------------------------------------------+
```

### Layout Division

- 상단 메타 영역: Type(1줄), Room(1줄), Thread(1줄), Scope(조건부 1줄) - 고정 높이
- 콘텐츠 영역: 나머지 공간 전체
- 하단 상태줄: 단축키 안내 + 전송 결과

### Content Area by Type

- text / markdown: `tui-textarea` 멀티라인 에디터
- image: 자체 단일행 입력 핸들러 (String + cursor) + Tab 자동완성
- image_multiple: 경로 목록 + 항목별 편집 모드

## Section 2: State Management and Key Handling

### ReplyModal State

```rust
struct ReplyModal {
    // 폼 필드
    reply_type: ReplyType,          // Text | Image | ImageMultiple | Markdown
    room: RoomSelection,            // 컨텍스트 자동 채움 or 선택
    thread: ThreadMode,             // None | Specified { id, scope }

    // 콘텐츠 (type별)
    text_area: TextArea<'static>,   // text / markdown
    image_path: String,             // image
    image_paths: Vec<String>,       // image_multiple

    // UI 상태
    focus: ModalFocus,
    result: Option<ReplyResult>,
    thread_suggestions: Vec<ThreadInfo>,
    room_list: Vec<RoomInfo>,
    sending: bool,
}

enum ModalFocus {
    Type,
    Room,
    RoomSelector,       // 방 목록 선택기 열림
    Thread,
    ThreadId,           // 스레드 지정 시 ID 입력
    ThreadSelector,     // 스레드 목록 선택기 열림
    Scope,
    Content,
}
```

### Key Event Routing

App 레벨에서 모달 존재 여부로 분기:

```
App::handle_key_event(key)
  +-- reply_modal.is_some() -> ReplyModal::handle_key(key)
  +-- else -> 기존 View 시스템
```

### Key Mapping

| 키 | 동작 | 조건 |
|----|------|------|
| Tab / Shift+Tab | 다음/이전 필드 | Content 외 포커스 |
| Left / Right | 옵션 전환 | Type / Scope 포커스 |
| Enter | 선택기 열기 | Room / Thread 포커스 |
| Ctrl+S | 전송 | 항상 (sending=false일 때) |
| Esc | 계층적 닫기 | Selector -> Field -> Modal |
| 문자 입력 | 에디터/입력에 위임 | Content / ThreadId 포커스 |

### Content Focus Isolation

Content 포커스일 때 tui-textarea가 키를 소비하므로:
- Tab은 textarea에 위임 (들여쓰기)
- Esc로만 Content 포커스 해제 (-> Type으로)
- Ctrl+S는 모달이 가로채서 전송

### Focus Cycle

```
Type -> Room -> Thread --+-- (없음) --> Content
                         +-- (지정) --> ThreadId -> Scope -> Content
```

Thread가 "없음"이면 ThreadId/Scope를 건너뜀.

## Section 3: API Layer

### Client Side (iris-common)

IrisApi에 메서드 추가:

```rust
// Reply 전송
async fn send_reply(&self, req: &ReplyRequest) -> Result<ReplyAcceptedResponse>
// POST /reply, HMAC 서명 자동

// 스레드 목록 조회 (신규 서버 API)
async fn list_threads(&self, chat_id: i64) -> Result<Vec<ThreadInfo>>
// GET /rooms/{chatId}/threads
```

### ReplyRequest 구조체

```rust
struct ReplyRequest {
    #[serde(rename = "type")]
    reply_type: ReplyType,    // "text" | "image" | "image_multiple" | "markdown"
    room: String,             // chatId (숫자 문자열)
    data: serde_json::Value,  // type별: 문자열 or 배열
    #[serde(skip_serializing_if = "Option::is_none")]
    thread_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    thread_scope: Option<u8>, // 1, 2, 3
}
```

이미지는 TUI에서 파일을 읽어 base64 인코딩 후 data 필드에 넣는다. 인코딩은 전송 직전에 수행.

### Server Side (app/ Kotlin)

MemberRoutes.kt에 엔드포인트 추가:

**GET /rooms/{chatId}/threads**

```json
[
  {
    "threadId": "12345",
    "lastMessage": "최근 메시지 미리보기...",
    "messageCount": 42,
    "lastActiveAt": 1743200000000
  }
]
```

쿼리: chat_logs 테이블에서 thread_id IS NOT NULL인 행을 GROUP BY thread_id로 집계.
MAX(created_at) 기준 최신순 정렬, 상위 20개.
HMAC 보호 적용 (기존 /rooms/* 과 동일).

## Section 4: Content Area Type-Specific Behavior

### text / markdown

- tui-textarea 멀티라인 에디터
- Ctrl+S 전송 시 textarea.lines().join("\n")을 data 문자열로 직렬화
- markdown은 type 필드값만 다름, 프리뷰 없음
- 빈 문자열이면 전송 차단 + 인라인 경고

### image

- 자체 단일행 입력 핸들러 (String + cursor position)
- Tab: 파일시스템 자동완성
- 검증 시점: Tab 완성 시, 포커스 이탈 시, 전송 시도 시 (매 키 입력마다 하지 않음)
- 전송 시: tokio::fs::read -> base64 인코딩 -> data 문자열
- 크기 한도: 원본 x 1.37 + JSON 오버헤드 < 48MB (실질 ~35MB)

### image_multiple

- Vec<String> 경로 목록
- Up/Down: 항목 간 커서 이동
- Enter: 선택 항목 편집 모드 진입
- Ctrl+A: 빈 항목 추가 후 자동 편집
- Ctrl+D: 선택 항목 삭제
- Tab: 편집 중 경로 자동완성
- 전송 시: 각 파일 base64 인코딩 -> data 배열
- 최소 1개 유효 경로 필요

### Path Autocomplete Behavior

- 현재 입력값을 디렉토리 + 프리픽스로 분리
- 디렉토리 내에서 프리픽스에 매칭되는 항목을 알파벳순으로 수집
- 첫 Tab: 공통 프리픽스까지 자동 완성. 후보가 1개면 전체 완성.
- 반복 Tab: 후보 간 순환 (bash 스타일이 아닌 zsh 스타일 cycling)
- 디렉토리 완성 시 trailing `/` 자동 추가
- 후보 없으면 무동작

### Pre-Send Validation

| 검증 | 실패 시 |
|------|---------|
| 콘텐츠 비어 있음 | 모달 하단 경고, 전송 차단 |
| 이미지 파일 미존재 | 경고 + 차단 |
| 총 body 크기 > 48MB | 경고 + 차단 (원본 ~35MB 기준) |
| room 미선택 | 경고 + Room 필드로 포커스 이동 |
| threadId 비숫자 | 경고 + ThreadId 필드로 포커스 이동 |

## Section 5: Send Flow and Error Handling

### Send Sequence

```
Ctrl+S
  |
  +- 1. Client validation (sync)
  |    +- room, content, image file, threadId
  |    +- fail -> modal warning + focus to field
  |
  +- 2. Payload assembly (async, tokio::spawn)
  |    +- text/markdown: lines.join("\n") -> data
  |    +- image: tokio::fs::read -> base64 -> data
  |
  +- 3. Sending state
  |    +- sending = true, Ctrl+S disabled
  |    +- modal footer: spinner
  |
  +- 4. API call (async)
  |    +- IrisApi::send_reply(&req)
  |
  +- 5. Result via channel
       +- reply_rx in tokio::select! (same pattern as sse_rx)
       +- 202 -> success display
       +- error -> error display
       +- sending = false
```

### Async Integration

전송은 tokio::spawn으로 분리, 결과를 mpsc 채널로 main 이벤트 루프에 전달.
기존 SSE 수신(sse_rx)과 동일한 패턴으로 tokio::select!에 reply_rx 분기 추가.

### Result Display

모달 하단 1줄 인라인, 색상 구분:

| 상황 | 색상 | 예시 |
|------|------|------|
| 검증 실패 | Yellow | 경고 메시지 |
| 전송 중 | Cyan | 스피너 |
| 202 성공 | Green | 전송 완료 (requestId) |
| 400 | Red | 잘못된 요청 상세 |
| 401 | Red | 인증 실패 |
| 429 | Yellow | 큐 포화 - 재시도 안내 |
| 503 | Red | 서비스 불가 |
| 네트워크 | Red | 연결 실패 |

### Post-Send Modal Behavior

- 성공(202): 모달 유지, Content만 초기화 -> 같은 방에 연속 전송 가능
- 실패: 모달 유지, 내용 보존 -> 수정 후 재전송 가능
- 닫기: Esc (sending=false일 때만)

## Thread UX

### Thread Mode

| 상태 | threadId | threadScope | 동작 |
|------|----------|-------------|------|
| 없음 (기본) | 생략 | 생략 | 방에 일반 메시지 전송 |
| 지정 | 입력/선택 | 1/2/3 | 기존 스레드 답장이든 새 스레드 열기든 동일 |

"답장"과 "스레드 열기"는 TUI 관점에서 동일한 동작 - threadId 지정 여부만 다르다.

### Scope Defaults

| 조건 | scope 기본값 |
|------|-------------|
| threadId 없음 | scope 비활성 (표시 안 함) |
| threadId 지정 | 2 (thread-only) |

선택지 순서: thread(2) -> both(3) -> room(1). 가장 흔한 케이스 우선.

### Thread Selector

스레드 목록 API(GET /rooms/{chatId}/threads)로 최근 활성 스레드를 조회해 편의 제공.
목록에 없는 메시지 ID를 직접 입력해도 동작한다.

## New Dependencies

| 크레이트 | 용도 |
|---------|------|
| tui-textarea | text/markdown 멀티라인 에디터 |
| base64 | 이미지 파일 base64 인코딩 |

## File Impact Summary

### New files (iris-ctl)

- `src/views/reply_modal.rs` - ReplyModal 구조체, 렌더링, 키 핸들링
- `src/views/path_input.rs` - 단일행 경로 입력 + 탭 자동완성

### Modified files (iris-ctl)

- `src/app.rs` - Option<ReplyModal> 추가, 'r' 키 핸들러, 모달 렌더링 오버레이
- `src/main.rs` - tokio::select!에 reply_rx 분기 추가
- `src/views/mod.rs` - reply_modal, path_input 모듈 선언
- `Cargo.toml` - tui-textarea, base64 의존성 추가

### Modified files (iris-common)

- `src/api.rs` - send_reply(), list_threads() 메서드 추가
- `src/models.rs` - ReplyRequest, ReplyAcceptedResponse, ThreadInfo 구조체

### New/Modified files (app/ Kotlin)

- `MemberRoutes.kt` - GET /rooms/{chatId}/threads 라우트 등록
- 신규 `ThreadQueries.kt` 또는 `RoomDirectoryQueries.kt` 확장 - thread 집계 쿼리
