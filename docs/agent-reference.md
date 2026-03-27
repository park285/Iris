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
H2cDispatcher
    | per-route coroutine Channel worker
    | OkHttp (h2c prior-knowledge or HTTP/1.1)
Webhook endpoint (external bot)
    |
POST /reply -> IrisServer -> Replier -> Android hidden API
```

## Code Style

- Kotlin/Java uses ktlint android mode and excludes generated code.
- Android lint runs through AGP with `./gradlew lint`.
- Commit messages use Korean. Conventional commit format is available when it helps readability.

## Frida Agent

session-chain 4-hook 아키텍처로 KakaoTalk process 안에 threadId/scope를 주입한다.

- URI fingerprint 기반 session correlation: `FingerprintSessionStore`, TTL 60s, max 32
- Admission checks confirm session, `threadScope`, `roomId`, and room match before injection
- Obfuscated field mapping follows the active KakaoTalk version

### Directory Layout

- `frida/agent/` — TS agent 정본
- `frida/daemon/` — Go watchdog
- `frida/legacy/` — Python daemon + JS agent rollback 경로
- `frida-modern/` — staging workspace. 동기화 후 `frida/`가 정본 역할을 가진다
- `node_modules/`, `generated/` — generated/runtime artifacts

## Testing Conventions

- Use JVM tests from `app/src/test/java/party/qwer/iris/`.
- Use bridge tests with `com.sun.net.httpserver.HttpServer` for real HTTP behavior.
- Use table-driven coverage and include failure cases for each new public API.
- Use `kotlin("test-junit")` as the current test dependency.

## Environment Variables

| Variable                        | Default                       | Purpose                       |
| ------------------------------- | ----------------------------- | ----------------------------- |
| `IRIS_CONFIG_PATH`              | `/data/local/tmp/config.json` | Config file path              |
| `IRIS_BOT_TOKEN`                | (none)                        | botToken env source           |
| `IRIS_WEBHOOK_TOKEN`            | (none)                        | webhookToken env source       |
| `IRIS_WEBHOOK_TRANSPORT`        | `h2c`                         | `http1` for HTTP/1.1          |
| `IRIS_LOG_LEVEL`                | `ERROR`                       | `DEBUG`/`INFO`/`ERROR`/`NONE` |
| `IRIS_DISABLE_HTTP`             | unset                         | `1` = disable IrisServer      |
| `IRIS_IMAGE_MEDIA_SCAN`         | `1`                           | `0` = disable media scan      |
| `IRIS_IMAGE_DELETE_INTERVAL_MS` | 3600000                       | Image cleanup interval        |
| `IRIS_IMAGE_RETENTION_MS`       | 86400000                      | Image retention period        |
| `IRIS_NOTIFICATION_REFERER`     | KakaoTalk prefs / `"Iris"`    | noti_referer value            |
| `IRIS_RUNNER`                   | `com.android.shell`           | calling package               |
| `KAKAOTALK_APP_UID`             | `0`                           | data mirror path uid          |

## Platform Notes

- Use broadcast intent based media scan in the `app_process` shell environment.
- Use `AndroidHiddenApi` for direct `IActivityManager` and `INotificationManager` access.
- Use `PathUtils` to select `/data_mirror/` or `/data/data/` automatically.
- Use `CommandFingerprint(chatId, userId, createdAt, message)` with LRU 256 for dedup.
- Skip `SYNCMSG` / `MCHATLOGS` messages with `NONE` kind to filter bot-origin messages.
- Use `/sdcard/Android/data/com.kakao.talk/files/iris-outbox-images` for outbound image storage.
- Use h2c for cleartext and fall back to HTTP/1.1 automatically for `https://` URLs.
- Save config with atomic move first and use `REPLACE_EXISTING` as the compatibility path.
