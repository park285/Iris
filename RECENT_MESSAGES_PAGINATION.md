# Recent Messages Pagination

이 문서는 `/query/recent-messages`를 사용하는 다음 클라이언트 작업, 특히 chatbotgo의 `!요약` 구현을 위한 계약과 처리 순서를 정리한다.

## API Contract

Endpoint:

```text
POST /query/recent-messages
```

Request body:

```json
{
  "chatId": 42,
  "limit": 300,
  "afterId": 123,
  "beforeId": null,
  "threadId": null
}
```

Fields:

| Field | Required | Meaning |
|-------|----------|---------|
| `chatId` | yes | Kakao chat room id |
| `limit` | no | Requested row count, clamped to `1..300` |
| `afterId` | no | Return messages whose `chat_logs.id` is greater than this id |
| `beforeId` | no | Return messages whose `chat_logs.id` is lower than this id |
| `threadId` | no | Restrict results to one thread |

`afterId` and `beforeId` are mutually exclusive. A request that sets both is rejected with `400 Bad Request`.

## Ordering

Ordering depends on the cursor mode:

| Mode | SQL filter | Ordering | Intended use |
|------|------------|----------|--------------|
| no cursor | `chat_id = ?` | `created_at DESC, id DESC` | Recent preview or ad hoc inspection |
| `afterId` | `chat_id = ? AND id > ?` | `id ASC` | Process the next unsummarized range |
| `beforeId` | `chat_id = ? AND id < ?` | `id DESC` | Page toward older messages |

For `!요약`, use `afterId`. The ascending id order lets the client summarize messages in natural progression and persist the highest returned `id` only after the summary succeeds.

## Chatbot Summary Flow

Recommended chatbotgo flow:

1. Load the last successful summary cursor for the room and optional thread.
2. Call `/query/recent-messages` with `limit: 300` and `afterId: lastSummaryMessageId`.
3. Drop empty/system messages and the current `!요약` command before prompt construction.
4. Enrich display names from `/rooms/{chatId}/members` when needed.
5. Summarize the returned batch in the response order.
6. Persist `max(messages[].id)` only after the summary response is sent successfully.
7. If the response contains exactly 300 messages, report that more unsummarized messages may remain and the next `!요약` can continue from the saved cursor.

If there is no saved cursor yet, the client can either initialize from the latest 300 messages without a cursor, or explicitly choose a starting cursor from a room-specific bootstrap policy. Do not claim coverage before the selected starting point.

## Rust Client

The `iris-common` crate keeps the existing call:

```rust
api.recent_messages(chat_id, limit).await?;
```

Cursor-aware callers should use:

```rust
api.recent_messages_with_cursor(chat_id, 300, Some(after_id), None, Some(thread_id)).await?;
```

The public `RecentMessagesRequest { chat_id, limit }` shape remains source-compatible. Cursor payloads use the public `RecentMessagesCursorRequest` model.

## Verification

Focused checks for this contract:

```bash
./gradlew :app:testDebugUnitTest --tests party.qwer.iris.ThreadQueriesTest --tests party.qwer.iris.http.QueryRoutesTest
cd tools && cargo test -p iris-common
cd tools && cargo fmt --check
./gradlew :app:ktlintTestDebugSourceSetCheck :app:ktlintMainSourceSetCheck
git diff --check
```

The full Rust workspace formatting gate is:

```bash
cd tools && cargo fmt --check
```
