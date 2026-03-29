# iris-ctl Reply Modal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** iris-ctl TUI에 컨텍스트 연동 Reply 모달을 추가하여, 어느 탭에서든 `r`을 눌러 text/image/image_multiple/markdown 메시지를 전송할 수 있게 한다.

**Architecture:** 세 레이어에 걸친 변경. (1) Kotlin 서버에 `GET /rooms/{chatId}/threads` 엔드포인트 추가, (2) iris-common에 Reply/Thread 모델 + `send_reply`/`list_threads` API 메서드, (3) iris-ctl에 ReplyModal 오버레이 + 비동기 전송 흐름.

**Tech Stack:** Rust (ratatui 0.30, tui-textarea, crossterm 0.29, tokio, base64), Kotlin (Ktor, kotlinx.serialization, SQLite via SqlClient)

**Spec:** `docs/superpowers/specs/2026-03-29-iris-ctl-reply-modal-design.md`

---

## File Structure

### New files

| File | Responsibility |
|------|---------------|
| `tools/iris-ctl/src/views/reply_modal.rs` | ReplyModal struct, rendering, key handling, validation |
| `tools/iris-ctl/src/views/path_input.rs` | Single-line path input with Tab autocomplete |
| `app/src/main/java/party/qwer/iris/storage/ThreadQueries.kt` | Thread listing SQL query |
| `app/src/main/java/party/qwer/iris/model/ThreadModels.kt` | ThreadSummary, ThreadListResponse |

### Modified files

| File | Change |
|------|--------|
| `tools/iris-common/src/models.rs` | Add ReplyRequest, ReplyAcceptedResponse, ThreadInfo, ReplyType |
| `tools/iris-common/src/api.rs` | Add signed_post_json, send_reply, list_threads |
| `tools/iris-common/Cargo.toml` | Add base64 dependency |
| `tools/iris-ctl/Cargo.toml` | Add tui-textarea dependency |
| `tools/iris-ctl/src/views/mod.rs` | Add reply_modal, path_input modules; OpenReply variant |
| `tools/iris-ctl/src/app.rs` | Add Option\<ReplyModal\>, overlay rendering, key routing |
| `tools/iris-ctl/src/main.rs` | Add reply_tx/reply_rx channel, tokio::select! arm |
| `app/src/main/java/party/qwer/iris/http/MemberRoutes.kt` | Add GET /rooms/{chatId}/threads route |
| `app/src/main/java/party/qwer/iris/MemberRepository.kt` | Add listThreads method |

---

## Task 1: Kotlin — Thread 모델과 쿼리

**Files:**
- Create: `app/src/main/java/party/qwer/iris/model/ThreadModels.kt`
- Create: `app/src/main/java/party/qwer/iris/storage/ThreadQueries.kt`
- Test: `app/src/test/java/party/qwer/iris/storage/ThreadQueriesTest.kt`

- [ ] **Step 1: ThreadModels.kt 작성**

```kotlin
package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class ThreadSummary(
    val threadId: String,
    val originMessage: String? = null,
    val lastMessage: String? = null,
    val messageCount: Int,
    val lastActiveAt: Long,
)

@Serializable
data class ThreadListResponse(
    val chatId: Long,
    val threads: List<ThreadSummary>,
)
```

- [ ] **Step 2: ThreadQueries.kt 작성**

`RoomDirectoryQueries`와 동일한 `SqlClient` + `QuerySpec` 패턴을 따른다.

```kotlin
package party.qwer.iris.storage

class ThreadQueries(
    private val db: SqlClient,
) {
    companion object {
        private const val MAX_THREADS = 20
    }

    data class ThreadRow(
        val threadId: Long,
        val messageCount: Int,
        val lastActiveAt: Long,
        val originMessage: String?,
        val originUserId: Long?,
        val originV: String?,
    )

    fun listThreads(chatId: ChatId): List<ThreadRow> =
        db.query(
            QuerySpec(
                sql = """
                    SELECT
                        t.thread_id,
                        t.msg_count,
                        t.last_active,
                        o.message AS origin_message,
                        o.user_id AS origin_user_id,
                        o.v AS origin_v
                    FROM (
                        SELECT thread_id, COUNT(*) AS msg_count, MAX(created_at) AS last_active
                        FROM chat_logs
                        WHERE chat_id = ? AND thread_id IS NOT NULL
                        GROUP BY thread_id
                        ORDER BY last_active DESC
                        LIMIT ?
                    ) t
                    LEFT JOIN chat_logs o ON o.id = t.thread_id
                """.trimIndent(),
                bindArgs = listOf(
                    SqlArg.LongVal(chatId.value),
                    SqlArg.IntVal(MAX_THREADS),
                ),
                maxRows = MAX_THREADS,
                mapper = { row ->
                    ThreadRow(
                        threadId = row.long("thread_id") ?: 0L,
                        messageCount = row.int("msg_count") ?: 0,
                        lastActiveAt = row.long("last_active") ?: 0L,
                        originMessage = row.string("origin_message"),
                        originUserId = row.long("origin_user_id"),
                        originV = row.string("origin_v"),
                    )
                },
            ),
        )
}
```

- [ ] **Step 3: ThreadQueriesTest.kt 작성**

```kotlin
package party.qwer.iris.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ThreadQueriesTest {

    @Test
    fun listThreads_returns_empty_when_no_threads() {
        val db = FakeSqlClient(emptyList())
        val queries = ThreadQueries(db)
        val result = queries.listThreads(ChatId(999L))
        assertEquals(0, result.size)
    }

    @Test
    fun listThreads_maps_row_with_origin() {
        val db = FakeSqlClient(listOf(
            mapOf(
                "thread_id" to 100L,
                "msg_count" to 5,
                "last_active" to 1774787702L,
                "origin_message" to "원본 메시지",
                "origin_user_id" to 42L,
                "origin_v" to """{"enc":31}""",
            )
        ))
        val queries = ThreadQueries(db)
        val result = queries.listThreads(ChatId(1L))
        assertEquals(1, result.size)
        val row = result[0]
        assertEquals(100L, row.threadId)
        assertEquals(5, row.messageCount)
        assertNotNull(row.originMessage)
    }

    @Test
    fun listThreads_handles_null_origin() {
        val db = FakeSqlClient(listOf(
            mapOf(
                "thread_id" to 200L,
                "msg_count" to 1,
                "last_active" to 1774787702L,
                "origin_message" to null,
                "origin_user_id" to null,
                "origin_v" to null,
            )
        ))
        val queries = ThreadQueries(db)
        val result = queries.listThreads(ChatId(1L))
        assertEquals(1, result.size)
        assertNull(result[0].originMessage)
    }
}
```

> **Note:** `FakeSqlClient`가 테스트 fixtures에 없으면 `RoomStatsQueriesTest`의 패턴을 확인하여 동일한 fake/stub 패턴을 사용한다.

- [ ] **Step 4: 테스트 실행**

Run: `./gradlew :app:testDebugUnitTest --tests "party.qwer.iris.storage.ThreadQueriesTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/model/ThreadModels.kt \
       app/src/main/java/party/qwer/iris/storage/ThreadQueries.kt \
       app/src/test/java/party/qwer/iris/storage/ThreadQueriesTest.kt
git commit -m "feat(app): add thread listing query and models"
```

---

## Task 2: Kotlin — MemberRepository + MemberRoutes 확장

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/MemberRepository.kt`
- Modify: `app/src/main/java/party/qwer/iris/http/MemberRoutes.kt`

- [ ] **Step 1: MemberRepository에 listThreads 메서드 추가**

`MemberRepository`에 `ThreadQueries` 의존성을 추가하고 `listThreads` 메서드를 구현한다. 원본 메시지 복호화를 위해 `ChatLogDecryptor.decryptRow`를 사용한다.

`MemberRepository` 생성자에 `ThreadQueries` 파라미터를 추가한다. 기존 생성자 호출부를 찾아서(`MemberRepository(` grep) 함께 수정한다.

```kotlin
fun listThreads(chatId: Long): ThreadListResponse {
    val room = roomDirectory.findRoomById(ChatId(chatId))
    val isOpenChat = room?.type?.startsWith("O") == true
    if (!isOpenChat) {
        return ThreadListResponse(chatId = chatId, threads = emptyList())
    }

    val rows = threadQueries.listThreads(ChatId(chatId))
    val threads = rows.map { row ->
        val decryptedOrigin = if (row.originMessage != null && row.originV != null) {
            val fakeRow = mapOf(
                "message" to row.originMessage,
                "user_id" to row.originUserId?.toString(),
                "v" to row.originV,
            )
            decryptRow(fakeRow, config)["message"]
        } else {
            null
        }
        ThreadSummary(
            threadId = row.threadId.toString(),
            originMessage = decryptedOrigin,
            messageCount = row.messageCount,
            lastActiveAt = row.lastActiveAt,
        )
    }
    return ThreadListResponse(chatId = chatId, threads = threads)
}
```

- [ ] **Step 2: MemberRoutes.kt에 라우트 추가**

`MemberRoutes.kt`의 `get("/rooms/{chatId}/members/{userId}/activity")` 블록 뒤(L50 이후), `if (bus != null)` 블록 앞에 추가:

```kotlin
get("/rooms/{chatId}/threads") {
    if (!authSupport.requireBotToken(call, method = "GET")) return@get
    val chatId = call.parameters["chatId"]?.toLongOrNull() ?: invalidRequest("chatId must be a number")
    call.respond(repo.listThreads(chatId))
}
```

- [ ] **Step 3: MemberRepository 생성자 수정**

`MemberRepository` 생성자에 `private val threadQueries: ThreadQueries` 파라미터를 추가한다. 생성자 호출부(보통 `Main.kt`나 DI 설정)에서 `ThreadQueries(sqlClient)` 인스턴스를 전달하도록 수정한다.

Run: `grep -rn "MemberRepository(" app/src/main/java/ --include="*.kt"` 로 호출부를 찾는다.

- [ ] **Step 4: 빌드 검증**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 테스트 실행**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (기존 테스트 + 새 ThreadQueriesTest 모두)

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/party/qwer/iris/MemberRepository.kt \
       app/src/main/java/party/qwer/iris/http/MemberRoutes.kt \
       app/src/main/java/party/qwer/iris/Main.kt
git commit -m "feat(app): add GET /rooms/{chatId}/threads endpoint"
```

---

## Task 3: iris-common — Reply/Thread 모델 추가

**Files:**
- Modify: `tools/iris-common/src/models.rs:209` (tests 블록 앞)
- Modify: `tools/iris-common/Cargo.toml`

- [ ] **Step 1: Cargo.toml에 base64 추가**

`tools/iris-common/Cargo.toml`의 `[dependencies]` 섹션에 추가:

```toml
base64 = "0.22"
```

- [ ] **Step 2: models.rs에 Reply/Thread 모델 추가**

`tools/iris-common/src/models.rs`의 `#[cfg(test)]` 블록 앞(L209 앞)에 추가. 기존 모델은 모두 `Deserialize`만 사용하지만, `ReplyRequest`는 클라이언트에서 서버로 보내므로 `Serialize`가 필요하다. import에 `use serde::Serialize;` 추가.

```rust
// --- Reply models ---

#[derive(Serialize, Debug, Clone)]
#[serde(rename_all = "snake_case")]
pub enum ReplyType {
    Text,
    Image,
    ImageMultiple,
    Markdown,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ReplyRequest {
    #[serde(rename = "type")]
    pub reply_type: ReplyType,
    pub room: String,
    pub data: serde_json::Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub thread_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub thread_scope: Option<u8>,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ReplyAcceptedResponse {
    pub success: bool,
    pub delivery: String,
    pub request_id: String,
    pub room: String,
    #[serde(rename = "type")]
    pub reply_type: String,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ErrorResponse {
    pub status: bool,
    pub message: String,
}

// --- Thread models ---

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ThreadListResponse {
    pub chat_id: i64,
    pub threads: Vec<ThreadSummary>,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ThreadSummary {
    pub thread_id: String,
    pub origin_message: Option<String>,
    pub message_count: i32,
    pub last_active_at: i64,
}
```

- [ ] **Step 3: 직렬화 테스트 추가**

`models.rs`의 `#[cfg(test)] mod tests` 블록 안에 추가:

```rust
#[test]
fn reply_request_serializes_correctly() {
    let req = ReplyRequest {
        reply_type: ReplyType::Text,
        room: "12345".to_string(),
        data: serde_json::Value::String("hello".to_string()),
        thread_id: Some("999".to_string()),
        thread_scope: Some(2),
    };
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["type"], "text");
    assert_eq!(json["room"], "12345");
    assert_eq!(json["data"], "hello");
    assert_eq!(json["threadId"], "999");
    assert_eq!(json["threadScope"], 2);
}

#[test]
fn reply_request_omits_none_thread_fields() {
    let req = ReplyRequest {
        reply_type: ReplyType::Markdown,
        room: "12345".to_string(),
        data: serde_json::Value::String("# title".to_string()),
        thread_id: None,
        thread_scope: None,
    };
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["type"], "markdown");
    assert!(json.get("threadId").is_none());
    assert!(json.get("threadScope").is_none());
}

#[test]
fn reply_accepted_response_parses() {
    let payload = r#"{"success":true,"delivery":"queued","requestId":"reply-abc","room":"123","type":"text"}"#;
    let parsed: ReplyAcceptedResponse = serde_json::from_str(payload).unwrap();
    assert_eq!(parsed.request_id, "reply-abc");
    assert!(parsed.success);
}

#[test]
fn thread_list_response_parses() {
    let payload = r#"{"chatId":123,"threads":[{"threadId":"456","originMessage":"원본","messageCount":5,"lastActiveAt":1774787702}]}"#;
    let parsed: ThreadListResponse = serde_json::from_str(payload).unwrap();
    assert_eq!(parsed.threads.len(), 1);
    assert_eq!(parsed.threads[0].origin_message.as_deref(), Some("원본"));
}
```

- [ ] **Step 4: 테스트 실행**

Run: `cargo test --manifest-path tools/iris-common/Cargo.toml`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add tools/iris-common/src/models.rs tools/iris-common/Cargo.toml
git commit -m "feat(iris-common): add Reply and Thread API models"
```

---

## Task 4: iris-common — send_reply + list_threads API 메서드

**Files:**
- Modify: `tools/iris-common/src/api.rs:181` (파일 끝)

- [ ] **Step 1: imports에 모델 추가**

`api.rs` L4의 import 블록에 추가:

```rust
use crate::models::{
    BridgeDiagnosticsResponse, HealthResponse, MemberActivityResponse, MemberListResponse,
    ReplyAcceptedResponse, ReplyRequest, RoomInfoResponse, RoomListResponse, StatsResponse,
    ThreadListResponse,
};
```

- [ ] **Step 2: signed_post_json 헬퍼 추가**

`signed_get` 메서드(L170) 뒤, `impl IrisApi` 닫는 `}` 앞에 추가:

```rust
fn signed_post_json<T: serde::Serialize>(
    &self,
    path: &str,
    body: &T,
) -> Result<reqwest::RequestBuilder> {
    let body_bytes = serde_json::to_vec(body)?;
    let target = canonical_target(path, &[]);
    Ok(self
        .client
        .post(format!("{}{}", self.base_url, target))
        .headers(signed_headers(&self.token, "POST", &target, &body_bytes)?)
        .header("Content-Type", "application/json")
        .body(body_bytes))
}
```

- [ ] **Step 3: send_reply 메서드 추가**

기존 public API 메서드들(health, ready, rooms 등) 뒤에 추가:

```rust
pub async fn send_reply(&self, req: &ReplyRequest) -> Result<ReplyAcceptedResponse> {
    let resp = self.signed_post_json("/reply", req)?.send().await?;
    if resp.status().is_success() {
        Ok(resp.json().await?)
    } else {
        let status = resp.status();
        let error: crate::models::ErrorResponse = resp.json().await.unwrap_or_else(|_| {
            crate::models::ErrorResponse {
                status: false,
                message: format!("HTTP {status}"),
            }
        });
        anyhow::bail!("[{status}] {}", error.message)
    }
}
```

- [ ] **Step 4: list_threads 메서드 추가**

```rust
pub async fn list_threads(&self, chat_id: i64) -> Result<ThreadListResponse> {
    Ok(self
        .signed_get(&format!("/rooms/{chat_id}/threads"), &[])?
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?)
}
```

- [ ] **Step 5: 빌드 검증**

Run: `cargo check --manifest-path tools/iris-common/Cargo.toml`
Expected: no errors

- [ ] **Step 6: 커밋**

```bash
git add tools/iris-common/src/api.rs
git commit -m "feat(iris-common): add send_reply and list_threads API methods"
```

---

## Task 5: iris-ctl — 의존성 추가 + ViewAction 확장

**Files:**
- Modify: `tools/iris-ctl/Cargo.toml`
- Modify: `tools/iris-ctl/src/views/mod.rs`

- [ ] **Step 1: Cargo.toml에 tui-textarea 추가**

`tools/iris-ctl/Cargo.toml`의 `[dependencies]` 섹션에 추가:

```toml
tui-textarea = "0.7"
base64 = "0.22"
```

- [ ] **Step 2: ViewAction에 OpenReply 추가**

`tools/iris-ctl/src/views/mod.rs`의 `ViewAction` enum(L25, `Back` 뒤)에 추가:

```rust
OpenReply(Option<i64>),
```

모듈 선언(L1-4)에 추가:

```rust
pub mod path_input;
pub mod reply_modal;
```

- [ ] **Step 3: 빌드 검증**

Run: `cargo check --manifest-path tools/iris-ctl/Cargo.toml`
Expected: warnings about unused modules (OK, 파일이 아직 없음) — 빈 모듈 파일을 먼저 생성:

`tools/iris-ctl/src/views/path_input.rs`:
```rust
// 단일행 경로 입력 + Tab 자동완성 위젯
```

`tools/iris-ctl/src/views/reply_modal.rs`:
```rust
// Reply 모달 오버레이
```

Run: `cargo check --manifest-path tools/iris-ctl/Cargo.toml`
Expected: no errors

- [ ] **Step 4: 커밋**

```bash
git add tools/iris-ctl/Cargo.toml tools/iris-ctl/src/views/mod.rs \
       tools/iris-ctl/src/views/path_input.rs tools/iris-ctl/src/views/reply_modal.rs
git commit -m "chore(iris-ctl): add tui-textarea, base64 deps and reply module stubs"
```

---

## Task 6: iris-ctl — PathInput 위젯

**Files:**
- Modify: `tools/iris-ctl/src/views/path_input.rs`

- [ ] **Step 1: PathInput 구조체 구현**

```rust
use crossterm::event::{KeyCode, KeyEvent};
use ratatui::layout::Rect;
use ratatui::style::{Color, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::Paragraph;
use ratatui::Frame;
use std::path::Path;

pub struct PathInput {
    pub value: String,
    cursor: usize,
    completions: Vec<String>,
    completion_index: Option<usize>,
    pub file_info: Option<FileInfo>,
}

pub struct FileInfo {
    pub exists: bool,
    pub size_bytes: u64,
    pub extension: String,
}

impl PathInput {
    pub fn new() -> Self {
        Self {
            value: String::new(),
            cursor: 0,
            completions: Vec::new(),
            completion_index: None,
            file_info: None,
        }
    }

    pub fn handle_key(&mut self, key: KeyEvent) -> bool {
        match key.code {
            KeyCode::Char(ch) => {
                self.value.insert(self.cursor, ch);
                self.cursor += ch.len_utf8();
                self.clear_completions();
                true
            }
            KeyCode::Backspace => {
                if self.cursor > 0 {
                    let prev = self.value[..self.cursor]
                        .char_indices()
                        .next_back()
                        .map(|(i, _)| i)
                        .unwrap_or(0);
                    self.value.replace_range(prev..self.cursor, "");
                    self.cursor = prev;
                    self.clear_completions();
                }
                true
            }
            KeyCode::Left => {
                if self.cursor > 0 {
                    self.cursor = self.value[..self.cursor]
                        .char_indices()
                        .next_back()
                        .map(|(i, _)| i)
                        .unwrap_or(0);
                }
                true
            }
            KeyCode::Right => {
                if self.cursor < self.value.len() {
                    self.cursor = self.value[self.cursor..]
                        .char_indices()
                        .nth(1)
                        .map(|(i, _)| self.cursor + i)
                        .unwrap_or(self.value.len());
                }
                true
            }
            KeyCode::Tab => {
                self.tab_complete();
                true
            }
            KeyCode::Home => {
                self.cursor = 0;
                true
            }
            KeyCode::End => {
                self.cursor = self.value.len();
                true
            }
            _ => false,
        }
    }

    fn clear_completions(&mut self) {
        self.completions.clear();
        self.completion_index = None;
    }

    fn tab_complete(&mut self) {
        if let Some(idx) = self.completion_index {
            // 순환: 다음 후보
            let next = (idx + 1) % self.completions.len();
            self.completion_index = Some(next);
            self.value = self.completions[next].clone();
            self.cursor = self.value.len();
        } else {
            // 후보 수집
            let candidates = self.collect_candidates();
            if candidates.is_empty() {
                return;
            }
            if candidates.len() == 1 {
                self.value = candidates[0].clone();
                self.cursor = self.value.len();
                return;
            }
            // 공통 프리픽스 완성
            let common = common_prefix(&candidates);
            if common.len() > self.value.len() {
                self.value = common;
                self.cursor = self.value.len();
            } else {
                // zsh 스타일 cycling
                self.completions = candidates;
                self.completion_index = Some(0);
                self.value = self.completions[0].clone();
                self.cursor = self.value.len();
            }
        }
        self.validate_file();
    }

    fn collect_candidates(&self) -> Vec<String> {
        let path = Path::new(&self.value);
        let (dir, prefix) = if self.value.ends_with('/') || self.value.ends_with(std::path::MAIN_SEPARATOR) {
            (path.to_path_buf(), "".to_string())
        } else {
            let dir = path.parent().unwrap_or(Path::new(".")).to_path_buf();
            let prefix = path
                .file_name()
                .map(|f| f.to_string_lossy().to_string())
                .unwrap_or_default();
            (dir, prefix)
        };

        let Ok(entries) = std::fs::read_dir(&dir) else {
            return Vec::new();
        };

        let mut candidates: Vec<String> = entries
            .filter_map(|e| e.ok())
            .filter(|e| {
                e.file_name()
                    .to_string_lossy()
                    .starts_with(&prefix)
            })
            .map(|e| {
                let mut full = dir.join(e.file_name()).to_string_lossy().to_string();
                if e.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                    full.push('/');
                }
                full
            })
            .collect();

        candidates.sort();
        candidates
    }

    pub fn validate_file(&mut self) {
        let path = Path::new(&self.value);
        if path.is_file() {
            let size = std::fs::metadata(path)
                .map(|m| m.len())
                .unwrap_or(0);
            let ext = path
                .extension()
                .map(|e| e.to_string_lossy().to_string())
                .unwrap_or_default();
            self.file_info = Some(FileInfo {
                exists: true,
                size_bytes: size,
                extension: ext,
            });
        } else {
            self.file_info = None;
        }
    }

    pub fn render(&self, frame: &mut Frame<'_>, area: Rect, focused: bool) {
        let style = if focused {
            Style::default().fg(Color::Yellow)
        } else {
            Style::default().fg(Color::White)
        };
        let display = if self.value.is_empty() {
            Line::from(Span::styled("경로를 입력하세요...", Style::default().fg(Color::DarkGray)))
        } else {
            Line::from(Span::styled(&self.value, style))
        };
        frame.render_widget(Paragraph::new(display), area);
        if focused {
            frame.set_cursor_position((area.x + self.cursor as u16, area.y));
        }
    }

    pub fn clear(&mut self) {
        self.value.clear();
        self.cursor = 0;
        self.clear_completions();
        self.file_info = None;
    }
}

fn common_prefix(strings: &[String]) -> String {
    if strings.is_empty() {
        return String::new();
    }
    let first = &strings[0];
    let mut len = first.len();
    for s in &strings[1..] {
        len = len.min(
            first
                .chars()
                .zip(s.chars())
                .take_while(|(a, b)| a == b)
                .count(),
        );
    }
    first.chars().take(len).collect()
}
```

- [ ] **Step 2: 단위 테스트 추가**

같은 파일 하단에:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    #[test]
    fn char_input_and_backspace() {
        let mut input = PathInput::new();
        input.handle_key(key(KeyCode::Char('a')));
        input.handle_key(key(KeyCode::Char('b')));
        assert_eq!(input.value, "ab");
        input.handle_key(key(KeyCode::Backspace));
        assert_eq!(input.value, "a");
    }

    #[test]
    fn cursor_movement() {
        let mut input = PathInput::new();
        input.handle_key(key(KeyCode::Char('a')));
        input.handle_key(key(KeyCode::Char('b')));
        input.handle_key(key(KeyCode::Left));
        input.handle_key(key(KeyCode::Char('x')));
        assert_eq!(input.value, "axb");
    }

    #[test]
    fn common_prefix_works() {
        assert_eq!(
            common_prefix(&["abc".into(), "abd".into(), "abe".into()]),
            "ab"
        );
        assert_eq!(common_prefix(&["same".into(), "same".into()]), "same");
        assert_eq!(common_prefix(&[]), "");
    }
}
```

- [ ] **Step 3: 테스트 실행**

Run: `cargo test --manifest-path tools/iris-ctl/Cargo.toml path_input`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add tools/iris-ctl/src/views/path_input.rs
git commit -m "feat(iris-ctl): add PathInput widget with tab autocomplete"
```

---

## Task 7: iris-ctl — ReplyModal 상태 + 렌더링

**Files:**
- Modify: `tools/iris-ctl/src/views/reply_modal.rs`

- [ ] **Step 1: ReplyModal 상태 구조체 구현**

```rust
use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use iris_common::models::{ReplyRequest, ReplyType, RoomSummary, ThreadSummary};
use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, Clear, Paragraph, Wrap};
use ratatui::Frame;
use tui_textarea::TextArea;

use super::path_input::PathInput;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum ModalFocus {
    Type,
    Room,
    RoomSelector,
    Thread,
    ThreadId,
    ThreadSelector,
    Scope,
    Content,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum ThreadMode {
    None,
    Specified,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum ThreadScope {
    Thread,  // 2
    Both,    // 3
    Room,    // 1
}

impl ThreadScope {
    fn value(self) -> u8 {
        match self {
            Self::Thread => 2,
            Self::Both => 3,
            Self::Room => 1,
        }
    }
    fn label(self) -> &'static str {
        match self {
            Self::Thread => "thread",
            Self::Both => "both",
            Self::Room => "room",
        }
    }
    fn next(self) -> Self {
        match self {
            Self::Thread => Self::Both,
            Self::Both => Self::Room,
            Self::Room => Self::Thread,
        }
    }
    fn prev(self) -> Self {
        match self {
            Self::Thread => Self::Room,
            Self::Room => Self::Both,
            Self::Both => Self::Thread,
        }
    }
}

#[derive(Clone)]
pub enum ReplyResult {
    Success { request_id: String },
    Error { message: String },
}

pub struct ReplyModal {
    // 폼 필드
    reply_type: ReplyType,
    room: Option<RoomSummary>,
    thread_mode: ThreadMode,
    thread_id_input: String,
    scope: ThreadScope,

    // 콘텐츠
    text_area: TextArea<'static>,
    image_path: PathInput,
    image_paths: Vec<PathInput>,
    image_paths_cursor: usize,
    image_editing: bool,

    // UI 상태
    pub focus: ModalFocus,
    pub result: Option<ReplyResult>,
    pub sending: bool,

    // 선택기 데이터
    pub room_list: Vec<RoomSummary>,
    room_selector_cursor: usize,
    pub thread_suggestions: Vec<ThreadSummary>,
    thread_selector_cursor: usize,

    // 오픈채팅 여부
    is_open_chat: bool,
}
```

- [ ] **Step 2: 생성자 구현**

```rust
impl ReplyModal {
    pub fn new(room: Option<RoomSummary>, room_list: Vec<RoomSummary>) -> Self {
        let is_open_chat = room
            .as_ref()
            .and_then(|r| r.room_type.as_deref())
            .map(|t| t.starts_with('O'))
            .unwrap_or(false);

        let mut text_area = TextArea::default();
        text_area.set_placeholder_text("메시지를 입력하세요...");
        text_area.set_block(Block::default().borders(Borders::ALL).title(" Content "));

        let initial_focus = if room.is_none() {
            ModalFocus::Room
        } else {
            ModalFocus::Type
        };

        Self {
            reply_type: ReplyType::Text,
            room,
            thread_mode: ThreadMode::None,
            thread_id_input: String::new(),
            scope: ThreadScope::Thread,
            text_area,
            image_path: PathInput::new(),
            image_paths: vec![PathInput::new()],
            image_paths_cursor: 0,
            image_editing: false,
            focus: initial_focus,
            result: None,
            sending: false,
            room_list,
            room_selector_cursor: 0,
            thread_suggestions: Vec::new(),
            thread_selector_cursor: 0,
            is_open_chat,
        }
    }
}
```

- [ ] **Step 3: render 메서드 구현**

```rust
impl ReplyModal {
    pub fn render(&self, frame: &mut Frame<'_>) {
        let area = centered_rect(frame.area(), 70, 80);
        frame.render_widget(Clear, area);

        let block = Block::default()
            .borders(Borders::ALL)
            .title(" Reply ")
            .style(Style::default().fg(Color::White));
        let inner = block.inner(area);
        frame.render_widget(block, area);

        // 메타 영역 높이 계산
        let meta_height = if self.is_open_chat && self.thread_mode == ThreadMode::Specified {
            4 // Type + Room + Thread + Scope
        } else if self.is_open_chat {
            3 // Type + Room + Thread
        } else {
            2 // Type + Room
        };

        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Length(meta_height),
                Constraint::Min(3),
                Constraint::Length(2),
            ])
            .split(inner);

        self.render_meta(frame, chunks[0]);
        self.render_content(frame, chunks[1]);
        self.render_footer(frame, chunks[2]);

        // 선택기 오버레이
        if self.focus == ModalFocus::RoomSelector {
            self.render_room_selector(frame, inner);
        }
        if self.focus == ModalFocus::ThreadSelector {
            self.render_thread_selector(frame, inner);
        }
    }

    fn render_meta(&self, frame: &mut Frame<'_>, area: Rect) {
        let rows = Layout::default()
            .direction(Direction::Vertical)
            .constraints(
                (0..area.height)
                    .map(|_| Constraint::Length(1))
                    .collect::<Vec<_>>(),
            )
            .split(area);

        let mut row_idx = 0;

        // Type row
        if row_idx < rows.len() {
            let type_line = self.render_type_line();
            frame.render_widget(Paragraph::new(type_line), rows[row_idx]);
            row_idx += 1;
        }

        // Room row
        if row_idx < rows.len() {
            let room_line = self.render_room_line();
            frame.render_widget(Paragraph::new(room_line), rows[row_idx]);
            row_idx += 1;
        }

        // Thread row (오픈채팅만)
        if self.is_open_chat && row_idx < rows.len() {
            let thread_line = self.render_thread_line();
            frame.render_widget(Paragraph::new(thread_line), rows[row_idx]);
            row_idx += 1;
        }

        // Scope row (Thread 지정 시만)
        if self.is_open_chat && self.thread_mode == ThreadMode::Specified && row_idx < rows.len() {
            let scope_line = self.render_scope_line();
            frame.render_widget(Paragraph::new(scope_line), rows[row_idx]);
        }
    }

    fn render_type_line(&self) -> Line<'static> {
        let focused = self.focus == ModalFocus::Type;
        let label_style = if focused {
            Style::default().fg(Color::Yellow).add_modifier(Modifier::BOLD)
        } else {
            Style::default().fg(Color::Gray)
        };
        let types = [
            (ReplyType::Text, "text"),
            (ReplyType::Image, "image"),
            (ReplyType::ImageMultiple, "images"),
            (ReplyType::Markdown, "markdown"),
        ];
        let mut spans = vec![Span::styled("  Type    ", label_style)];
        for (rt, label) in &types {
            let selected = std::mem::discriminant(rt) == std::mem::discriminant(&self.reply_type);
            let marker = if selected { "●" } else { "○" };
            let style = if selected {
                Style::default().fg(Color::Cyan)
            } else {
                Style::default().fg(Color::DarkGray)
            };
            spans.push(Span::styled(format!("{marker} {label}  "), style));
        }
        Line::from(spans)
    }

    fn render_room_line(&self) -> Line<'static> {
        let focused = self.focus == ModalFocus::Room;
        let label_style = if focused {
            Style::default().fg(Color::Yellow).add_modifier(Modifier::BOLD)
        } else {
            Style::default().fg(Color::Gray)
        };
        let room_text = match &self.room {
            Some(r) => {
                let name = r.link_name.as_deref().unwrap_or("Unknown");
                format!("{name} ({})", r.chat_id)
            }
            None => "선택 안 됨 (Enter)".to_string(),
        };
        Line::from(vec![
            Span::styled("  Room    ", label_style),
            Span::styled(room_text, Style::default().fg(Color::White)),
        ])
    }

    fn render_thread_line(&self) -> Line<'static> {
        let focused = matches!(self.focus, ModalFocus::Thread | ModalFocus::ThreadId);
        let label_style = if focused {
            Style::default().fg(Color::Yellow).add_modifier(Modifier::BOLD)
        } else {
            Style::default().fg(Color::Gray)
        };
        let thread_text = match self.thread_mode {
            ThreadMode::None => "없음 (Enter: 지정)".to_string(),
            ThreadMode::Specified => {
                if self.focus == ModalFocus::ThreadId {
                    format!("ID: {}█", self.thread_id_input)
                } else if self.thread_id_input.is_empty() {
                    "지정됨 (Enter: 선택/입력)".to_string()
                } else {
                    format!("ID: {}", self.thread_id_input)
                }
            }
        };
        Line::from(vec![
            Span::styled("  Thread  ", label_style),
            Span::styled(thread_text, Style::default().fg(Color::White)),
        ])
    }

    fn render_scope_line(&self) -> Line<'static> {
        let focused = self.focus == ModalFocus::Scope;
        let label_style = if focused {
            Style::default().fg(Color::Yellow).add_modifier(Modifier::BOLD)
        } else {
            Style::default().fg(Color::Gray)
        };
        let scopes = [ThreadScope::Thread, ThreadScope::Both, ThreadScope::Room];
        let mut spans = vec![Span::styled("  Scope   ", label_style)];
        for s in &scopes {
            let selected = *s == self.scope;
            let marker = if selected { "●" } else { "○" };
            let style = if selected {
                Style::default().fg(Color::Cyan)
            } else {
                Style::default().fg(Color::DarkGray)
            };
            spans.push(Span::styled(format!("{marker} {}  ", s.label()), style));
        }
        Line::from(spans)
    }

    fn render_content(&self, frame: &mut Frame<'_>, area: Rect) {
        match self.reply_type {
            ReplyType::Text | ReplyType::Markdown => {
                frame.render_widget(&self.text_area, area);
            }
            ReplyType::Image => {
                let block = Block::default().borders(Borders::ALL).title(" Image Path ");
                let inner = block.inner(area);
                frame.render_widget(block, area);

                let chunks = Layout::default()
                    .direction(Direction::Vertical)
                    .constraints([Constraint::Length(1), Constraint::Min(0)])
                    .split(inner);

                self.image_path.render(frame, chunks[0], self.focus == ModalFocus::Content);
                if let Some(info) = &self.image_path.file_info {
                    let size_kb = info.size_bytes / 1024;
                    let text = format!("  ✓ 파일 존재 ({size_kb} KB, {})", info.extension.to_uppercase());
                    frame.render_widget(
                        Paragraph::new(text).style(Style::default().fg(Color::Green)),
                        chunks[1],
                    );
                }
            }
            ReplyType::ImageMultiple => {
                let block = Block::default().borders(Borders::ALL).title(" Image Paths ");
                let inner = block.inner(area);
                frame.render_widget(block, area);

                let constraints: Vec<Constraint> = self
                    .image_paths
                    .iter()
                    .enumerate()
                    .map(|(i, _)| {
                        if i == self.image_paths_cursor && self.image_editing {
                            Constraint::Length(2)
                        } else {
                            Constraint::Length(1)
                        }
                    })
                    .chain(std::iter::once(Constraint::Min(0)))
                    .collect();

                let rows = Layout::default()
                    .direction(Direction::Vertical)
                    .constraints(constraints)
                    .split(inner);

                for (i, path_input) in self.image_paths.iter().enumerate() {
                    if i >= rows.len() - 1 {
                        break;
                    }
                    let marker = if i == self.image_paths_cursor { "▶" } else { " " };
                    let idx_text = format!("{marker}{}. ", i + 1);

                    let row_chunks = Layout::default()
                        .direction(Direction::Horizontal)
                        .constraints([Constraint::Length(4), Constraint::Min(0)])
                        .split(rows[i]);

                    frame.render_widget(
                        Paragraph::new(idx_text).style(Style::default().fg(Color::DarkGray)),
                        row_chunks[0],
                    );

                    let is_focused = self.focus == ModalFocus::Content
                        && i == self.image_paths_cursor
                        && self.image_editing;
                    path_input.render(frame, row_chunks[1], is_focused);
                }

                // 하단 키 안내
                if let Some(last_row) = rows.last() {
                    frame.render_widget(
                        Paragraph::new("  Ctrl+A 추가 │ Ctrl+D 삭제 │ ↑↓ 이동 │ Enter 편집")
                            .style(Style::default().fg(Color::DarkGray)),
                        *last_row,
                    );
                }
            }
        }
    }

    fn render_footer(&self, frame: &mut Frame<'_>, area: Rect) {
        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([Constraint::Length(1), Constraint::Length(1)])
            .split(area);

        // 단축키 안내
        frame.render_widget(
            Paragraph::new("  Ctrl+S 전송 │ Tab 다음 │ Esc 취소")
                .style(Style::default().fg(Color::DarkGray)),
            chunks[0],
        );

        // 결과 표시
        if self.sending {
            frame.render_widget(
                Paragraph::new("  ⟳ 전송 중...").style(Style::default().fg(Color::Cyan)),
                chunks[1],
            );
        } else if let Some(result) = &self.result {
            let (text, style) = match result {
                ReplyResult::Success { request_id } => (
                    format!("  ✓ 전송 완료 ({request_id})"),
                    Style::default().fg(Color::Green),
                ),
                ReplyResult::Error { message } => (
                    format!("  ✗ {message}"),
                    Style::default().fg(Color::Red),
                ),
            };
            frame.render_widget(Paragraph::new(text).style(style), chunks[1]);
        }
    }

    fn render_room_selector(&self, frame: &mut Frame<'_>, parent: Rect) {
        let area = centered_rect(parent, 60, 50);
        frame.render_widget(Clear, area);
        let block = Block::default().borders(Borders::ALL).title(" 방 선택 ");
        let inner = block.inner(area);
        frame.render_widget(block, area);

        let rows: Vec<Line> = self
            .room_list
            .iter()
            .enumerate()
            .map(|(i, r)| {
                let name = r.link_name.as_deref().unwrap_or("Unknown");
                let style = if i == self.room_selector_cursor {
                    Style::default().fg(Color::Yellow).add_modifier(Modifier::BOLD)
                } else {
                    Style::default().fg(Color::White)
                };
                Line::styled(format!("  {} ({})", name, r.chat_id), style)
            })
            .collect();

        frame.render_widget(
            Paragraph::new(rows).wrap(Wrap { trim: false }),
            inner,
        );
    }

    fn render_thread_selector(&self, frame: &mut Frame<'_>, parent: Rect) {
        let area = centered_rect(parent, 70, 50);
        frame.render_widget(Clear, area);
        let block = Block::default().borders(Borders::ALL).title(" 스레드 선택 ");
        let inner = block.inner(area);
        frame.render_widget(block, area);

        if self.thread_suggestions.is_empty() {
            frame.render_widget(
                Paragraph::new("  스레드 없음").style(Style::default().fg(Color::DarkGray)),
                inner,
            );
            return;
        }

        let rows: Vec<Line> = self
            .thread_suggestions
            .iter()
            .enumerate()
            .map(|(i, t)| {
                let origin = t.origin_message.as_deref().unwrap_or("(원본 없음)");
                let truncated = if origin.len() > 40 {
                    format!("{}...", &origin[..40])
                } else {
                    origin.to_string()
                };
                let style = if i == self.thread_selector_cursor {
                    Style::default().fg(Color::Yellow).add_modifier(Modifier::BOLD)
                } else {
                    Style::default().fg(Color::White)
                };
                Line::styled(
                    format!("  [{}건] {truncated}", t.message_count),
                    style,
                )
            })
            .collect();

        frame.render_widget(Paragraph::new(rows), inner);
    }
}

fn centered_rect(area: Rect, percent_x: u16, percent_y: u16) -> Rect {
    let vertical = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Percentage((100 - percent_y) / 2),
            Constraint::Percentage(percent_y),
            Constraint::Percentage((100 - percent_y) / 2),
        ])
        .split(area);
    Layout::default()
        .direction(Direction::Horizontal)
        .constraints([
            Constraint::Percentage((100 - percent_x) / 2),
            Constraint::Percentage(percent_x),
            Constraint::Percentage((100 - percent_x) / 2),
        ])
        .split(vertical[1])[1]
}
```

- [ ] **Step 4: 빌드 검증**

Run: `cargo check --manifest-path tools/iris-ctl/Cargo.toml`
Expected: warnings about unused fields/methods (OK, 키 핸들링 아직 없음), no errors

- [ ] **Step 5: 커밋**

```bash
git add tools/iris-ctl/src/views/reply_modal.rs
git commit -m "feat(iris-ctl): add ReplyModal state struct and rendering"
```

---

## Task 8: iris-ctl — ReplyModal 키 핸들링

**Files:**
- Modify: `tools/iris-ctl/src/views/reply_modal.rs`

- [ ] **Step 1: handle_key + 포커스 순환 구현**

`ReplyModal` impl 블록에 추가:

```rust
impl ReplyModal {
    /// true를 반환하면 모달 닫기
    pub fn handle_key(&mut self, key: KeyEvent) -> ModalAction {
        // Ctrl+S: 전송
        if key.modifiers.contains(KeyModifiers::CONTROL)
            && matches!(key.code, KeyCode::Char('s'))
        {
            if !self.sending {
                return self.try_send();
            }
            return ModalAction::None;
        }

        // Content 포커스: textarea/path가 대부분의 키를 소비
        if self.focus == ModalFocus::Content {
            return self.handle_content_key(key);
        }

        // 선택기 열려 있을 때
        if self.focus == ModalFocus::RoomSelector {
            return self.handle_room_selector_key(key);
        }
        if self.focus == ModalFocus::ThreadSelector {
            return self.handle_thread_selector_key(key);
        }

        // ThreadId 입력
        if self.focus == ModalFocus::ThreadId {
            return self.handle_thread_id_key(key);
        }

        match key.code {
            KeyCode::Tab => {
                self.focus = self.next_focus();
                ModalAction::None
            }
            KeyCode::BackTab => {
                self.focus = self.prev_focus();
                ModalAction::None
            }
            KeyCode::Left | KeyCode::Right => {
                self.handle_option_switch(key.code);
                ModalAction::None
            }
            KeyCode::Enter => self.handle_enter(),
            KeyCode::Esc => ModalAction::Close,
            _ => ModalAction::None,
        }
    }

    fn next_focus(&self) -> ModalFocus {
        match self.focus {
            ModalFocus::Type => ModalFocus::Room,
            ModalFocus::Room => {
                if self.is_open_chat {
                    ModalFocus::Thread
                } else {
                    ModalFocus::Content
                }
            }
            ModalFocus::Thread => {
                if self.thread_mode == ThreadMode::Specified {
                    ModalFocus::ThreadId
                } else {
                    ModalFocus::Content
                }
            }
            ModalFocus::ThreadId => ModalFocus::Scope,
            ModalFocus::Scope => ModalFocus::Content,
            ModalFocus::Content => ModalFocus::Content, // Esc로만 탈출
            _ => self.focus,
        }
    }

    fn prev_focus(&self) -> ModalFocus {
        match self.focus {
            ModalFocus::Room => ModalFocus::Type,
            ModalFocus::Thread => ModalFocus::Room,
            ModalFocus::ThreadId => ModalFocus::Thread,
            ModalFocus::Scope => ModalFocus::ThreadId,
            ModalFocus::Content => ModalFocus::Content, // Esc로만 탈출
            _ => self.focus,
        }
    }

    fn handle_option_switch(&mut self, code: KeyCode) {
        match self.focus {
            ModalFocus::Type => {
                let types = [
                    ReplyType::Text,
                    ReplyType::Image,
                    ReplyType::ImageMultiple,
                    ReplyType::Markdown,
                ];
                let cur = types
                    .iter()
                    .position(|t| std::mem::discriminant(t) == std::mem::discriminant(&self.reply_type))
                    .unwrap_or(0);
                let next = match code {
                    KeyCode::Right => (cur + 1) % types.len(),
                    KeyCode::Left => (cur + types.len() - 1) % types.len(),
                    _ => cur,
                };
                self.reply_type = types[next].clone();
            }
            ModalFocus::Scope => {
                self.scope = match code {
                    KeyCode::Right => self.scope.next(),
                    KeyCode::Left => self.scope.prev(),
                    _ => self.scope,
                };
            }
            ModalFocus::Thread => {
                self.thread_mode = match code {
                    KeyCode::Right | KeyCode::Left => match self.thread_mode {
                        ThreadMode::None => ThreadMode::Specified,
                        ThreadMode::Specified => ThreadMode::None,
                    },
                    _ => self.thread_mode,
                };
            }
            _ => {}
        }
    }

    fn handle_enter(&mut self) -> ModalAction {
        match self.focus {
            ModalFocus::Room => {
                self.focus = ModalFocus::RoomSelector;
                ModalAction::None
            }
            ModalFocus::Thread => {
                if self.thread_mode == ThreadMode::Specified {
                    self.focus = ModalFocus::ThreadSelector;
                    ModalAction::FetchThreads(self.room.as_ref().map(|r| r.chat_id).unwrap_or(0))
                } else {
                    self.thread_mode = ThreadMode::Specified;
                    self.focus = ModalFocus::ThreadId;
                    ModalAction::None
                }
            }
            _ => ModalAction::None,
        }
    }

    fn handle_content_key(&mut self, key: KeyEvent) -> ModalAction {
        if key.code == KeyCode::Esc {
            self.focus = ModalFocus::Type;
            return ModalAction::None;
        }
        match self.reply_type {
            ReplyType::Text | ReplyType::Markdown => {
                self.text_area.input(key);
            }
            ReplyType::Image => {
                self.image_path.handle_key(key);
            }
            ReplyType::ImageMultiple => {
                self.handle_image_multiple_key(key);
            }
        }
        ModalAction::None
    }

    fn handle_image_multiple_key(&mut self, key: KeyEvent) {
        if self.image_editing {
            match key.code {
                KeyCode::Esc | KeyCode::Enter => {
                    self.image_editing = false;
                    if let Some(p) = self.image_paths.get_mut(self.image_paths_cursor) {
                        p.validate_file();
                    }
                }
                _ => {
                    if let Some(p) = self.image_paths.get_mut(self.image_paths_cursor) {
                        p.handle_key(key);
                    }
                }
            }
            return;
        }

        if key.modifiers.contains(KeyModifiers::CONTROL) {
            match key.code {
                KeyCode::Char('a') => {
                    self.image_paths.push(PathInput::new());
                    self.image_paths_cursor = self.image_paths.len() - 1;
                    self.image_editing = true;
                }
                KeyCode::Char('d') => {
                    if self.image_paths.len() > 1 {
                        self.image_paths.remove(self.image_paths_cursor);
                        if self.image_paths_cursor >= self.image_paths.len() {
                            self.image_paths_cursor = self.image_paths.len() - 1;
                        }
                    }
                }
                _ => {}
            }
            return;
        }

        match key.code {
            KeyCode::Up => {
                if self.image_paths_cursor > 0 {
                    self.image_paths_cursor -= 1;
                }
            }
            KeyCode::Down => {
                if self.image_paths_cursor + 1 < self.image_paths.len() {
                    self.image_paths_cursor += 1;
                }
            }
            KeyCode::Enter => {
                self.image_editing = true;
            }
            _ => {}
        }
    }

    fn handle_room_selector_key(&mut self, key: KeyEvent) -> ModalAction {
        match key.code {
            KeyCode::Up => {
                if self.room_selector_cursor > 0 {
                    self.room_selector_cursor -= 1;
                }
            }
            KeyCode::Down => {
                if self.room_selector_cursor + 1 < self.room_list.len() {
                    self.room_selector_cursor += 1;
                }
            }
            KeyCode::Enter => {
                if let Some(selected) = self.room_list.get(self.room_selector_cursor).cloned() {
                    self.is_open_chat = selected
                        .room_type
                        .as_deref()
                        .map(|t| t.starts_with('O'))
                        .unwrap_or(false);
                    self.room = Some(selected);
                    if !self.is_open_chat {
                        self.thread_mode = ThreadMode::None;
                    }
                }
                self.focus = ModalFocus::Room;
            }
            KeyCode::Esc => {
                self.focus = ModalFocus::Room;
            }
            _ => {}
        }
        ModalAction::None
    }

    fn handle_thread_selector_key(&mut self, key: KeyEvent) -> ModalAction {
        match key.code {
            KeyCode::Up => {
                if self.thread_selector_cursor > 0 {
                    self.thread_selector_cursor -= 1;
                }
            }
            KeyCode::Down => {
                if self.thread_selector_cursor + 1 < self.thread_suggestions.len() {
                    self.thread_selector_cursor += 1;
                }
            }
            KeyCode::Enter => {
                if let Some(selected) = self.thread_suggestions.get(self.thread_selector_cursor) {
                    self.thread_id_input = selected.thread_id.clone();
                }
                self.focus = ModalFocus::ThreadId;
            }
            KeyCode::Esc => {
                self.focus = ModalFocus::Thread;
            }
            _ => {}
        }
        ModalAction::None
    }

    fn handle_thread_id_key(&mut self, key: KeyEvent) -> ModalAction {
        match key.code {
            KeyCode::Char(ch) if ch.is_ascii_digit() => {
                self.thread_id_input.push(ch);
            }
            KeyCode::Backspace => {
                self.thread_id_input.pop();
            }
            KeyCode::Tab => {
                self.focus = self.next_focus();
            }
            KeyCode::Esc => {
                self.focus = ModalFocus::Thread;
            }
            _ => {}
        }
        ModalAction::None
    }
}

pub enum ModalAction {
    None,
    Close,
    Send(ReplyRequest),
    FetchThreads(i64),
}
```

- [ ] **Step 2: try_send 검증 + 페이로드 조립**

```rust
impl ReplyModal {
    fn try_send(&mut self) -> ModalAction {
        self.result = None;

        // room 검증
        let room = match &self.room {
            Some(r) => r.chat_id.to_string(),
            None => {
                self.result = Some(ReplyResult::Error {
                    message: "room을 선택해주세요".to_string(),
                });
                self.focus = ModalFocus::Room;
                return ModalAction::None;
            }
        };

        // thread 검증
        let (thread_id, thread_scope) = match self.thread_mode {
            ThreadMode::None => (None, None),
            ThreadMode::Specified => {
                if self.thread_id_input.is_empty() {
                    self.result = Some(ReplyResult::Error {
                        message: "threadId를 입력해주세요".to_string(),
                    });
                    self.focus = ModalFocus::ThreadId;
                    return ModalAction::None;
                }
                if self.thread_id_input.parse::<i64>().is_err() {
                    self.result = Some(ReplyResult::Error {
                        message: "threadId는 숫자여야 합니다".to_string(),
                    });
                    self.focus = ModalFocus::ThreadId;
                    return ModalAction::None;
                }
                (
                    Some(self.thread_id_input.clone()),
                    Some(self.scope.value()),
                )
            }
        };

        // data 조립은 type별
        let data = match &self.reply_type {
            ReplyType::Text | ReplyType::Markdown => {
                let text = self.text_area.lines().join("\n");
                if text.trim().is_empty() {
                    self.result = Some(ReplyResult::Error {
                        message: "메시지를 입력해주세요".to_string(),
                    });
                    return ModalAction::None;
                }
                serde_json::Value::String(text)
            }
            ReplyType::Image => {
                if self.image_path.value.is_empty() {
                    self.result = Some(ReplyResult::Error {
                        message: "이미지 경로를 입력해주세요".to_string(),
                    });
                    return ModalAction::None;
                }
                // 실제 파일 읽기 + base64는 async에서 처리
                // 여기서는 경로만 전달하고 main에서 인코딩
                serde_json::Value::String(self.image_path.value.clone())
            }
            ReplyType::ImageMultiple => {
                let paths: Vec<String> = self
                    .image_paths
                    .iter()
                    .filter(|p| !p.value.is_empty())
                    .map(|p| p.value.clone())
                    .collect();
                if paths.is_empty() {
                    self.result = Some(ReplyResult::Error {
                        message: "이미지 경로를 하나 이상 입력해주세요".to_string(),
                    });
                    return ModalAction::None;
                }
                serde_json::Value::Array(
                    paths.into_iter().map(serde_json::Value::String).collect(),
                )
            }
        };

        self.sending = true;

        ModalAction::Send(ReplyRequest {
            reply_type: self.reply_type.clone(),
            room,
            data,
            thread_id,
            thread_scope,
        })
    }

    pub fn set_result(&mut self, result: ReplyResult) {
        self.sending = false;
        if matches!(result, ReplyResult::Success { .. }) {
            // 성공 시 Content만 초기화
            self.text_area = TextArea::default();
            self.text_area.set_placeholder_text("메시지를 입력하세요...");
            self.text_area.set_block(
                Block::default()
                    .borders(Borders::ALL)
                    .title(" Content "),
            );
            self.image_path.clear();
            self.image_paths = vec![PathInput::new()];
            self.image_paths_cursor = 0;
        }
        self.result = Some(result);
    }
}
```

- [ ] **Step 3: 빌드 검증**

Run: `cargo check --manifest-path tools/iris-ctl/Cargo.toml`
Expected: no errors

- [ ] **Step 4: 커밋**

```bash
git add tools/iris-ctl/src/views/reply_modal.rs
git commit -m "feat(iris-ctl): add ReplyModal key handling and validation"
```

---

## Task 9: iris-ctl — App 통합 (오버레이 + 키 라우팅)

**Files:**
- Modify: `tools/iris-ctl/src/app.rs`

- [ ] **Step 1: App 구조체에 모달 필드 추가**

`app.rs` L5 imports에 추가:

```rust
use crate::views::reply_modal::{ModalAction, ReplyModal, ReplyResult};
```

`App` struct(L20 뒤)에 추가:

```rust
pub reply_modal: Option<ReplyModal>,
```

`App::new()`(L31 뒤)에 추가:

```rust
reply_modal: None,
```

- [ ] **Step 2: render에 모달 오버레이 추가**

`App::render`에서 status bar 렌더링(L58) 뒤에 추가:

```rust
if let Some(modal) = &self.reply_modal {
    modal.render(frame);
}
```

- [ ] **Step 3: handle_key_event에 모달 우선 라우팅**

`handle_key_event`의 `key.kind != Press` 체크(L97) 뒤, Ctrl+C 체크(L98) 앞에 추가:

```rust
if let Some(modal) = &mut self.reply_modal {
    let action = modal.handle_key(key);
    return match action {
        ModalAction::Close => {
            self.reply_modal = None;
            false
        }
        ModalAction::Send(req) => {
            // main.rs에서 처리하도록 별도 플래그로 전달
            self.pending_reply = Some(req);
            false
        }
        ModalAction::FetchThreads(chat_id) => {
            self.pending_thread_fetch = Some(chat_id);
            false
        }
        ModalAction::None => false,
    };
}
```

`App` struct에 pending 필드 추가:

```rust
pub pending_reply: Option<ReplyRequest>,
pub pending_thread_fetch: Option<i64>,
```

`App::new()`에 초기화:

```rust
pending_reply: None,
pending_thread_fetch: None,
```

- [ ] **Step 4: apply_action에 OpenReply 처리 추가**

`apply_action`의 `ViewAction::None`(L91) 앞에 추가:

```rust
ViewAction::OpenReply(chat_id) => {
    let room = chat_id.and_then(|id| {
        self.rooms_view.rooms.iter().find(|r| r.chat_id == id).cloned()
    });
    self.reply_modal = Some(ReplyModal::new(room, self.rooms_view.rooms.clone()));
    false
}
```

- [ ] **Step 5: 'r' 키 바인딩 추가**

`handle_key_event`에서 `KeyCode::Char('q')` 뒤(L102)에 추가:

```rust
KeyCode::Char('r') => {
    let chat_id = match self.active_tab {
        TabId::Rooms => self.rooms_view.selected_chat_id(),
        TabId::Members | TabId::Stats => self.members_view.chat_id,
        TabId::Events => None,
    };
    return self.apply_action(ViewAction::OpenReply(chat_id));
}
```

> **Note:** `rooms_view.selected_chat_id()`가 없으면 현재 선택된 행의 chat_id를 반환하는 메서드를 `RoomsView`에 추가한다. `rooms_view`의 테이블 상태에서 선택된 인덱스로 `rooms[index].chat_id`를 반환.

- [ ] **Step 6: 기존 테스트 수정**

`App::new()`의 변경으로 인해 기존 테스트에서 컴파일 오류가 나지 않는지 확인. `App::new()`가 `reply_modal: None`, `pending_reply: None`, `pending_thread_fetch: None`으로 초기화하므로 기존 테스트는 영향 없음.

Run: `cargo test --manifest-path tools/iris-ctl/Cargo.toml`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add tools/iris-ctl/src/app.rs tools/iris-ctl/src/views/rooms.rs
git commit -m "feat(iris-ctl): integrate ReplyModal into App with overlay and key routing"
```

---

## Task 10: iris-ctl — 비동기 전송 흐름

**Files:**
- Modify: `tools/iris-ctl/src/main.rs`

- [ ] **Step 1: reply 채널 설정**

`main()` 함수에서 SSE 채널 설정(L131) 뒤에 추가:

```rust
let (reply_tx, mut reply_rx) = mpsc::unbounded_channel::<ReplyResult>();
```

import에 추가:

```rust
use app::ReplyResult;  // re-export가 필요하면 app.rs에서 pub use
```

실제로는 `reply_modal::ReplyResult`를 사용. `app.rs`에서 re-export:

```rust
pub use crate::views::reply_modal::ReplyResult;
```

- [ ] **Step 2: tokio::select!에 reply_rx arm 추가**

`tokio::select!` 루프(L150–162)에 arm 추가:

```rust
reply_result = reply_rx.recv() => {
    if let Some(result) = reply_result {
        if let Some(modal) = &mut app.reply_modal {
            modal.set_result(result);
        }
    }
}
```

- [ ] **Step 3: pending_reply 처리 로직**

`tokio::select!` 루프의 terminal_event arm 안에서, `handle_terminal_stream_event` 호출 뒤에 pending 처리를 추가. `loop` 블록 안, `terminal.draw` 뒤에:

```rust
// pending reply 처리
if let Some(req) = app.pending_reply.take() {
    let api = iris.clone();
    let tx = reply_tx.clone();
    tokio::spawn(async move {
        let result = send_reply_async(&api, req).await;
        let _ = tx.send(result);
    });
}

// pending thread fetch 처리
if let Some(chat_id) = app.pending_thread_fetch.take() {
    if let Ok(thread_list) = iris.list_threads(chat_id).await {
        if let Some(modal) = &mut app.reply_modal {
            modal.thread_suggestions = thread_list.threads;
        }
    }
}
```

- [ ] **Step 4: send_reply_async 함수 구현**

`main.rs`의 `refresh_rooms` 함수 근처에 추가:

```rust
async fn send_reply_async(iris: &api::TuiApi, req: iris_common::models::ReplyRequest) -> app::ReplyResult {
    use base64::Engine;
    use iris_common::models::ReplyType;

    // 이미지 타입이면 파일을 읽어서 base64 인코딩
    let final_req = match &req.reply_type {
        ReplyType::Image => {
            let path = req.data.as_str().unwrap_or("");
            match tokio::fs::read(path).await {
                Ok(bytes) => {
                    // ~35MB 원본 상한 (base64 후 ~48MB)
                    const MAX_RAW_BYTES: u64 = 35 * 1024 * 1024;
                    if bytes.len() as u64 > MAX_RAW_BYTES {
                        return app::ReplyResult::Error {
                            message: format!("파일이 너무 큽니다 ({:.1} MB, 상한 35 MB)", bytes.len() as f64 / 1_048_576.0),
                        };
                    }
                    let encoded = base64::engine::general_purpose::STANDARD.encode(&bytes);
                    iris_common::models::ReplyRequest {
                        data: serde_json::Value::String(encoded),
                        ..req
                    }
                }
                Err(e) => {
                    return app::ReplyResult::Error {
                        message: format!("파일 읽기 실패: {e}"),
                    };
                }
            }
        }
        ReplyType::ImageMultiple => {
            let paths: Vec<&str> = req
                .data
                .as_array()
                .map(|arr| arr.iter().filter_map(|v| v.as_str()).collect())
                .unwrap_or_default();
            let mut encoded_list = Vec::new();
            let mut total_bytes: u64 = 0;
            const MAX_RAW_BYTES: u64 = 35 * 1024 * 1024;
            for path in paths {
                match tokio::fs::read(path).await {
                    Ok(bytes) => {
                        total_bytes += bytes.len() as u64;
                        if total_bytes > MAX_RAW_BYTES {
                            return app::ReplyResult::Error {
                                message: format!("이미지 합산 크기가 너무 큽니다 ({:.1} MB, 상한 35 MB)", total_bytes as f64 / 1_048_576.0),
                            };
                        }
                        encoded_list.push(serde_json::Value::String(
                            base64::engine::general_purpose::STANDARD.encode(&bytes),
                        ));
                    }
                    Err(e) => {
                        return app::ReplyResult::Error {
                            message: format!("파일 읽기 실패 ({path}): {e}"),
                        };
                    }
                }
            }
            iris_common::models::ReplyRequest {
                data: serde_json::Value::Array(encoded_list),
                ..req
            }
        }
        _ => req,
    };

    match iris.send_reply(&final_req).await {
        Ok(resp) => app::ReplyResult::Success {
            request_id: resp.request_id,
        },
        Err(e) => app::ReplyResult::Error {
            message: e.to_string(),
        },
    }
}
```

- [ ] **Step 5: 빌드 검증**

Run: `cargo check --manifest-path tools/iris-ctl/Cargo.toml`
Expected: no errors

- [ ] **Step 6: 전체 테스트 실행**

Run: `cargo test --manifest-path tools/iris-ctl/Cargo.toml`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add tools/iris-ctl/src/main.rs tools/iris-ctl/src/app.rs
git commit -m "feat(iris-ctl): add async reply send flow with channel integration"
```

---

## Task 11: 전체 빌드 검증 + Clippy

**Files:** (수정 없음, 검증만)

- [ ] **Step 1: Kotlin 빌드 + 테스트**

Run: `./gradlew :app:ktlintCheck :app:lint :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Rust 포맷 + 린트**

Run: `cargo fmt --manifest-path tools/iris-ctl/Cargo.toml --check`
Expected: no diff

Run: `cargo clippy --manifest-path tools/iris-ctl/Cargo.toml --all-targets -- -D warnings`
Expected: no warnings (clippy 경고가 있으면 수정)

- [ ] **Step 3: Rust 전체 테스트**

Run: `cargo test --manifest-path tools/iris-ctl/Cargo.toml`
Expected: PASS

Run: `cargo test --manifest-path tools/iris-common/Cargo.toml`
Expected: PASS

- [ ] **Step 4: clippy 경고 수정 (있을 경우)**

주로 발생할 수 있는 clippy 경고:
- `clippy::too_many_arguments` → `#[allow]` 어노테이션
- `clippy::needless_pass_by_value` → 참조로 변경
- `clippy::cognitive_complexity` → 함수 분리

수정 후 다시 clippy 실행하여 clean 상태 확인.

- [ ] **Step 5: 최종 커밋 (수정이 있었을 경우)**

```bash
git add -u
git commit -m "style(iris-ctl): fix clippy warnings and formatting"
```
