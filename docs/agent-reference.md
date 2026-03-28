# Agent Reference

Supporting reference for Iris prompt and agent work.
Use this file for supporting reference that is helpful on demand.

## Project Overview

Iris는 KakaoTalk SQLite DB를 polling하여 채팅 메시지를 감지하고, HTTP/2 cleartext (h2c) webhook으로 외부 봇 서버에 전달하는 headless Android bot framework다.
root `app_process`로 Redroid 컨테이너에서 실행한다.

- **Runtime:** `CLASSPATH=Iris.apk app_process / party.qwer.iris.Main`
- **Package:** `party.qwer.iris`
- **SDK:** minSdk/targetSdk/compileSdk 35
- **Language level:** Java/Kotlin 21

## Operations Reference

- Canonical local source and build workspace: `/home/kapu/gemini/Iris`
- Runtime config source of truth: `/home/kapu/gemini/llm/configs/iris/iris.env`
- Remote staging path: `/root/work/Iris`
- Remote runtime-config path: `/root/work/arm-iris-runtime`
- Preferred SSH path: `ssh -i /home/kapu/gemini/hololive-bot/KR.key root@100.100.1.4`
- Backup policy: create a timestamped `.backup-*` directory before replacing remote APKs or critical scripts

## Architecture

```text
KakaoTalk DB (SQLite)
    | poll every N ms
DBObserver -> ObserverHelper
    | decrypt + parse command
    | CommandParser (!, /, // prefix)
    | resolveWebhookRoute
OutboxRoutingGateway
    | file-backed outbox + checkpoint advance
WebhookOutboxDispatcher
    | route/room partition coroutine worker
    | OkHttp (h2c prior-knowledge or HTTP/1.1)
Webhook endpoint (external bot)
    |
POST /reply -> IrisServer -> Replier -> Android hidden API
```

## Code Style

- Kotlin/Java uses ktlint android mode and excludes generated code.
- Android lint runs through AGP with `./gradlew lint`.
- Commit messages use Korean. Conventional commit format is available when it helps readability.

## Image Bridge

image reply는 앱 프로세스에서 직접 Kakao sender를 건드리지 않고, 로컬 UDS를 통해 LSPosed bridge module로 넘긴다.

- `ReplyService`는 이미지 payload를 파일로 준비한 뒤 `UdsImageReplySender`로 위임한다.
- `UdsImageBridgeClient`는 로컬 소켓으로 room/thread metadata와 image path를 bridge에 전달한다.
- bridge module은 KakaoTalk process 안에서 runtime class discovery 후 sender entry method를 호출한다.
- 현재 실측 기준 `threadScope=2`는 thread-only, `threadScope=3`은 thread + room 같이 보내기 의미로 사용한다.

## Markdown Reply

markdown reply는 `/reply-markdown` route가 Kakao share intent를 열고, LSPosed/Xposed hook이 `ChatSendingLogRequest$a.u(...)` 단계에서 필요한 thread metadata를 graft한다.

- out-thread markdown은 Kakao share path 그대로 전송된다.
- in-thread markdown은 Iris extras로 전달된 `roomId/threadId/threadScope`를 bridge가 캡처한 뒤 request dispatch 직전에 `sendingLog`에 주입한다.
- `/reply`의 in-thread text도 현재는 같은 share/graft lane을 사용한다.
- `/reply-markdown`은 markdown attachment를 강제로 켜는 호환 alias로 유지된다.

### Directory Layout

- `app/` — HTTP admission, queueing, UDS client, webhook dispatch
- `bridge/` — LSPosed/Xposed image bridge module and in-process Kakao sender integration
- `bridge/src/main/java/party/qwer/iris/imagebridge/runtime/` — image bridge runtime (class discovery, sender, thread graft)
- `app/src/main/java/party/qwer/iris/delivery/webhook/` — webhook delivery and outbox path

## Testing Conventions

- Use JVM tests from `app/src/test/java/party/qwer/iris/`.
- Use bridge tests with `com.sun.net.httpserver.HttpServer` for real HTTP behavior.
- Use table-driven coverage and include failure cases for each new public API.
- Use `kotlin("test-junit")` as the current test dependency.

## Environment Variables

| Variable                        | Default                       | Purpose                       |
| ------------------------------- | ----------------------------- | ----------------------------- |
| `IRIS_CONFIG_PATH`              | `/data/local/tmp/config.json` | Config file path              |
| `IRIS_BRIDGE_TOKEN`             | (none)                        | bridge handshake token        |
| `IRIS_BIND_HOST`                | `127.0.0.1`                   | Iris HTTP bind host           |
| `IRIS_HTTP_WORKER_THREADS`      | `2`                           | Netty worker thread count     |
| `IRIS_BRIDGE_HEALTH_REFRESH_MS` | `5000`                        | bridge health snapshot refresh |
| `IRIS_WEBHOOK_TRANSPORT`        | `h2c`                         | `http1` for HTTP/1.1          |
| `IRIS_LOG_LEVEL`                | `ERROR`                       | `DEBUG`/`INFO`/`ERROR`/`NONE` |
| `IRIS_DISABLE_HTTP`             | unset                         | `1` = disable IrisServer      |
| `IRIS_IMAGE_MEDIA_SCAN`         | `1`                           | `0` = disable media scan      |
| `IRIS_IMAGE_DELETE_INTERVAL_MS` | 3600000                       | Image cleanup interval        |
| `IRIS_IMAGE_RETENTION_MS`       | 86400000                      | Image retention period        |
| `IRIS_NOTIFICATION_REFERER`     | KakaoTalk prefs / `"Iris"`    | noti_referer value            |
| `IRIS_RUNNER`                   | `com.kakao.talk` (AndroidHiddenApi) / `com.android.shell` (HiddenNotificationFeed) | calling package (용도별 기본값 상이) |
| `KAKAOTALK_APP_UID`             | `0`                           | data mirror path uid          |

## Platform Notes

- Use broadcast intent based media scan in the `app_process` shell environment.
- Use `AndroidHiddenApi` for direct `IActivityManager` and `INotificationManager` access.
- Use `PathUtils` to select `/data_mirror/` or `/data/data/` automatically.
- Use `CommandFingerprint(chatId, userId, createdAt, message)` with LRU 256 for dedup.
- Skip `SYNCMSG` / `MCHATLOGS` messages with `NONE` kind to filter bot-origin messages.
- Use `/sdcard/Android/data/com.kakao.talk/files/iris-outbox-images` for outbound image storage.
- Default webhook route is `default`.
- Use h2c for cleartext and fall back to HTTP/1.1 automatically for `https://` URLs.
- Save config with temp write + `fd.sync()` + atomic move.
