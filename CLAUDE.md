# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Output Language

All output (explanations, comments in code, commit messages, PR descriptions) MUST be in Korean (한국어, 합쇼체).
Technical terms, code identifiers, and CLI commands remain in English.

## Dependency Version Policy

**NEVER trust model training data for library versions.** Always verify latest versions via external sources before suggesting upgrades:

1. **context7 MCP** — `resolve-library-id` then `query-docs` for official docs
2. **WebSearch** — search `"<library> latest version maven central"` or check the official release page
3. **WebFetch** — fetch Maven Central search API: `https://search.maven.org/solrsearch/select?q=g:<group>+AND+a:<artifact>&rows=1&wt=json` and read `response.docs[0].latestVersion`

Current versions are pinned in `gradle/libs.versions.toml`. When the user asks to update dependencies, check each one against the live source above before proposing changes.

## Build & Test Commands

```bash
# Full verification pipeline (lint + build)
./gradlew lint ktlintCheck assembleDebug assembleRelease

# Unit tests only (JVM, no Android emulator needed)
./gradlew test

# Single test class
./gradlew test --tests "party.qwer.iris.CommandParserTest"

# Single test method
./gradlew test --tests "party.qwer.iris.CommandParserTest.testSpecificMethod"

# Auto-fix ktlint violations
./gradlew ktlintFormat
```

Build artifacts land in `output/Iris-debug.apk` and `output/Iris-release.apk`.

## Project Overview

Iris is a headless Android bot framework that polls KakaoTalk's SQLite database, detects chat messages, and dispatches them as HTTP/2 cleartext (h2c) webhooks to external bot servers. It runs as a root `app_process` shell process on a Redroid (Android-on-Linux) container — **not** as a regular Android app.

**Runtime:** `CLASSPATH=Iris.apk app_process / party.qwer.iris.Main`
**Package:** `party.qwer.iris` | minSdk 35, targetSdk 35, compileSdk 35, Java/Kotlin 21

## Architecture

```
KakaoTalk DB (SQLite)
    | poll every N ms
DBObserver -> ObserverHelper
    | decrypt + parse command
    | CommandParser (!, /, // prefix)
    | resolveWebhookRoute (hololive / chatbotgo / settlement)
H2cDispatcher
    | per-route coroutine Channel worker
    | OkHttp (h2c prior-knowledge or HTTP/1.1)
Webhook endpoint (external bot)
    |
POST /reply -> IrisServer -> Replier -> Android hidden API
```

### Core Components

| File | Role |
|------|------|
| `Main.kt` | Entry point. Wires components, registers shutdown hook |
| `IrisServer.kt` | Ktor/Netty HTTP server: `/health`, `/ready`, `/config`, `/reply`, `/query` |
| `ObserverHelper.kt` | Processes DB poll results: decrypt, parse, dedup, dispatch |
| `DBObserver.kt` | Coroutine-based polling loop (`Configurable.dbPollingRate`) |
| `KakaoDB.kt` | SQLiteDatabase wrapper. ATTACHes KakaoTalk.db + KakaoTalk2.db + multi_profile_database.db |
| `Replier.kt` | Sends text/image via Android hidden API (NotificationActionService, ACTION_SEND_MULTIPLE) |
| `Configurable.kt` | Runtime config singleton with snapshot/effective dual state, atomic file save |
| `CommandParser.kt` | `!`/`/` -> WEBHOOK, `//` -> COMMENT, else -> NONE |
| `KakaoDecrypt.kt` | AES/CBC + PBKDF2 decryption (based on jiru/kakaodecrypt) |
| `AndroidHiddenApi.kt` | Reflection-based IActivityManager access (multi Android version) |
| `HiddenNotificationFeed.kt` | INotificationManager reflection for active notification queries |
| `KakaoProfileIndexer.kt` | Periodic notification scan -> DB upsert (digest-based dedup) |
| `ObservedThreadMetadata.kt` | Extracts thread metadata from thread_id / supplement JSON |

### bridge/ Package

| File | Role |
|------|------|
| `H2cDispatcher.kt` | Per-route Channel + coroutine worker. OkHttp webhook POST with backoff retry |
| `RoutingModels.kt` | `RoutingCommand`, `RoutingResult` (ACCEPTED/SKIPPED/RETRY_LATER) |
| `WebhookRouter.kt` | `resolveWebhookRoute()` — command text -> route name |
| `DeliveryRetryPolicy.kt` | Exponential backoff schedule computation |

## Key Design Decisions

### Config: snapshot vs effective
- `snapshot`: value persisted to disk (applied immediately for most fields)
- `effective`: value currently in use (some fields require restart)
- `botHttpPort` change updates snapshot only; effective stays until restart -> `pendingRestart=true`

### Webhook Routing (WebhookRouter.kt)
- `!정산`, `!정산완료` -> `settlement`
- `!질문`, `!리셋`, `!관리자` -> `chatbotgo`
- Other `!` or `/` prefix -> `hololive` (default route)
- Per-route endpoint from `Configurable.webhookEndpointFor(route)`, falls back to `endpoint` (default)

### Dispatcher Queue Semantics
- Per-route Channel capacity: 64
- Max retry: 6, backoff: 1s/2s/4s/8s/16s/30s (+ 500ms jitter)
- Retriable status: 408, 429, 5xx
- Queue saturation -> `RETRY_LATER` (ObserverHelper does not advance lastLogId)
- Shutdown: drain up to 10s, then cancel remaining
- No persistence — in-memory queue is lost on restart

### Reply Admission (`/reply`)
- 202 Accepted = queued in memory, not guaranteed KakaoTalk delivery
- Channel capacity: 64 (`MESSAGE_CHANNEL_CAPACITY`)
- QUEUE_FULL -> 429, SHUTDOWN -> 503, INVALID_PAYLOAD -> 400

### Auth (fail-closed)
- `botToken` unset -> 503 (protected APIs never open)
- `botToken` mismatch -> 401
- Empty `webhookToken`/`botToken` in config -> env var fallback (`IRIS_WEBHOOK_TOKEN`, `IRIS_BOT_TOKEN`)

### Thread Reply
- `text` type only. Sending `threadId` with `image`/`image_multiple` -> 400
- `threadScope=1` allowed without `threadId` (chatroom-wide scope)
- `threadScope>1` requires `threadId`

## Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `IRIS_CONFIG_PATH` | `/data/local/tmp/config.json` | Config file path |
| `IRIS_BOT_TOKEN` | (none) | Fallback when botToken is empty in config |
| `IRIS_WEBHOOK_TOKEN` | (none) | Fallback when webhookToken is empty in config |
| `IRIS_WEBHOOK_TRANSPORT` | `h2c` | Set `http1` to force HTTP/1.1 |
| `IRIS_LOG_LEVEL` | `ERROR` | `DEBUG`, `INFO`, `ERROR`, `NONE` |
| `IRIS_DISABLE_HTTP` | unset | `1` disables IrisServer |
| `IRIS_IMAGE_MEDIA_SCAN` | `1` | `0` disables media scan broadcast |
| `IRIS_IMAGE_DELETE_INTERVAL_MS` | 3600000 (1h) | Image cleanup interval |
| `IRIS_IMAGE_RETENTION_MS` | 86400000 (1d) | Image retention period |
| `IRIS_NOTIFICATION_REFERER` | KakaoTalk prefs or `"Iris"` | noti_referer value for reply |
| `IRIS_RUNNER` | `com.android.shell` | AndroidHiddenApi calling package |
| `KAKAOTALK_APP_UID` | `0` | KakaoTalk data mirror path uid |

## Static Analysis

- **Android Lint:** AGP 내장, `./gradlew lint`로 실행
- **ktlint:** android mode, failure on violation, excludes generated code

## Testing Conventions

- All tests are JVM-only under `app/src/test/java/party/qwer/iris/` — no Android instrumentation tests
- Bridge tests spin up real HTTP servers via `com.sun.net.httpserver.HttpServer`
- Table-driven style preferred. New public APIs must include failure cases
- Test dependency: `kotlin("test-junit")` only

## Constraints & Gotchas

- **No Android Context:** `app_process` shell — `MediaScannerConnection` unavailable, use broadcast intent instead
- **Reflection required:** `AndroidHiddenApi` accesses IActivityManager/INotificationManager directly
- **DB path auto-detection:** `PathUtils` resolves `/data_mirror/data_ce/null/$UID/com.kakao.talk/` vs `/data/data/com.kakao.talk/`
- **Bot ID auto-detection:** matches `"isMine":true` string in `chat_logs.v`, then JSON fallback
- **Duplicate command prevention:** `CommandFingerprint(chatId, userId, createdAt, message)` with LRU cap of 256
- **Origin skip rule:** `SYNCMSG`/`MCHATLOGS` origin + NONE kind -> skip (filters out bot's own messages)
- **Image path:** `/sdcard/Android/data/com.kakao.talk/files/iris-outbox-images` (Iris-specific, not KakaoTalk shared root)
- **HTTPS:** h2c prior-knowledge is cleartext only. `https://` URLs automatically use HTTP/1.1 client
- **Config file save:** atomic move (ATOMIC_MOVE -> REPLACE_EXISTING fallback)
