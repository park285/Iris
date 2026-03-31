# Iris - 안드로이드 네이티브 DB기반 봇 프레임워크

이 프로젝트는 카카오톡 안드로이드 앱의 데이터베이스와 연동하여 HTTP webhook/h2c 기반 채팅 봇을 작성할 수 있는 환경을 제공합니다.

**프로젝트 상태:** 베타

## 현재 상태 요약

- headless Redroid 런타임 기준으로 운영 중입니다.
- outbound webhook 전송은 SQLite WAL-backed durable outbox + route/room partition worker가 담당합니다.
- route별 전송은 room partition 기준으로 backlog를 격리하고 restart 후에도 pending delivery를 재개합니다.
- 보호 API는 `X-Iris-Timestamp` / `X-Iris-Nonce` / `X-Iris-Signature` 기반 signed request만 허용합니다.
- `/config`는 `inboundSigningSecret`으로, `/reply`/`/reply-status`/`/rooms*`/`/events/stream`/`/query/*`/`/diagnostics/*`는 `botControlToken`으로 보호됩니다.
- HTTP 관리 API 기본 bind host는 `127.0.0.1`이며, 외부 노출은 `IRIS_BIND_HOST`로 명시적으로만 엽니다.
- image reply는 로컬 UDS + LSPosed bridge 경로로 전송합니다.
- 운영 검증 기준 최신 배포는 `iris-kr` 환경에서 반영되었고, 인증 포함 `/config`, `/query` 스모크를 통과했습니다.

## 시작하기

### 필요 조건

*   **안드로이드 기기:** 이 애플리케이션은 카카오톡이 설치되어 있는 안드로이드 기기에서 실행되도록 설계되었습니다.
*   **루트 권한:** 카카오톡 데이터베이스와 일부 시스템 서비스에 접근하기 위하여 루트 권한이 필요합니다.
*   **백엔드 통신:** 내부 네트워크에서 접근 가능한 webhook/h2c 엔드포인트가 필요합니다.

### 설치

1.  **Release artifact 사용**

    최신 Iris를 [Releases](https://github.com/park285/Iris/releases)에서 다운로드하거나,
    아래처럼 `iris_control`로 바로 설치하세요.

    ```bash
    chmod +x iris_control
    ./iris_control install
    ```

    `iris_control`은 Linux host 기준 스크립트입니다.

2.  **로컬 빌드 artifact 사용**

    로컬 빌드 결과물은 `output/Iris-debug.apk` 또는 `output/Iris-release.apk` 로 복사됩니다.
    장치 실행 파일명은 항상 `/data/local/tmp/Iris.apk` 기준으로 맞추세요.

    ```bash
    adb push output/Iris-debug.apk /data/local/tmp/Iris.apk
    ```

3.  **실행**

    ```bash
    ./iris_control start
    ```

    `iris_control`은 `install/start/status/stop` 명령어를 제공합니다.
    완전 무로그가 필요하면 `IRIS_LOG_LEVEL=NONE ./iris_control start`처럼 실행할 수 있습니다.

4.  **Config 설정**

    `/data/iris/config.json` (또는 `IRIS_CONFIG_PATH`)에 설정 파일을 배치하세요.
    기본 런타임 베이스 디렉터리를 바꾸려면 `IRIS_DATA_DIR`를 사용하고, 개별 파일만 바꿀 때만 `IRIS_CONFIG_PATH`를 override하세요.

---

## webhook/h2c 기반 봇 통합 가이드 (내부 통신 권장)

### 아키텍처 개요

Iris는 비동기 큐 + route별 코루틴 워커 기반으로 봇과 직접 통신합니다:

```
카카오톡 메시지
    ↓
AppRuntime (lifecycle orchestrator)
    ├─ KakaoDB → KakaoDbRuntime (DB connection 추상화)
    │    ├─ KakaoChatLogReader (채팅 로그 조회)
    │    └─ KakaoIdentityReader (사용자/프로필 조회)
    ├─ DBObserver → ObserverHelper (DB polling + 명령 파싱)
    │    └─ OutboxRoutingGateway → SqliteWebhookDeliveryStore (durable outbox)
    │         └─ WebhookOutboxDispatcher (route/room partition 코루틴 워커)
    │              → HTTP POST (h2c 우선) → Bot Webhook
    ├─ IrisServer (HTTP API) + RequestAuthenticator (HMAC 인증)
    │    └─ allowlisted query routes (`/query/room-summary`, `/query/member-stats`, `/query/recent-threads`)
    ├─ ReplyService → UdsImageReplySender
    │    └─ UdsImageBridgeClient → local UDS → LSPosed bridge → 카카오톡 전송
    ├─ IrisMetadataStore (iris.db 기반 저장소)
    ├─ CheckpointStore (checkpoint 파일 분리)
    └─ BridgeHealthCache (bridge health 캐시)
```

### Iris 설정

`/data/iris/config.json` (또는 `IRIS_CONFIG_PATH`)에서 webhook을 설정합니다.

```json
{
  "botName": "Iris",
  "botHttpPort": 3000,
  "endpoint": "http://<DEFAULT_REACHABLE_HOST_IP>:30001/webhook/iris",
  "webhooks": {
    "default": "http://<DEFAULT_REACHABLE_HOST_IP>:30001/webhook/iris",
    "chatbotgo": "http://<CHATBOTGO_HOST_IP>:31001/webhook/iris",
    "settlement": "http://<SETTLEMENT_HOST_IP>:30002/webhook/iris"
  },
  "inboundSigningSecret": "<REQUIRED_SHARED_TOKEN>",
  "outboundWebhookToken": "<REQUIRED_SHARED_TOKEN>",
  "botControlToken": "<REQUIRED_SHARED_TOKEN>",
  "bridgeToken": "<REQUIRED_BRIDGE_TOKEN>",
  "dbPollingRate": 100,
  "messageSendRate": 50
}
```

설명:
- `endpoint`: route별 설정이 없는 경우 사용하는 기본 webhook endpoint
- `webhooks.<route>`: route별 override endpoint. 예: `default`, `chatbotgo`, `settlement`
- `webhooks.default`가 기본 route endpoint입니다.
- `inboundSigningSecret`: `/config` 보호 API 호출 시 signed request HMAC 서명 키입니다.
- `outboundWebhookToken`: outbound webhook 전송 시 `X-Iris-Token` 헤더로 전달되는 토큰입니다.
- `botControlToken`: `/reply`, `/reply-status`, `/rooms*`, `/events/stream`, `/query/*`, `/diagnostics/*` 보호 API 호출 시 사용하는 control-plane 서명 키입니다.
- `bridgeToken`: app process와 LSPosed bridge가 공유하는 UDS handshake 토큰입니다. 운영에서는 `config.json`의 명시 필드로 유지해야 합니다.
- 역할별 secret이 비어 있으면 해당 보호 API는 `503 service unavailable`으로 거부됩니다.
- `IRIS_BIND_HOST` 기본값은 `127.0.0.1`이고, `IRIS_HTTP_WORKER_THREADS`로 Netty worker 수를 조정할 수 있습니다.
- snapshot missing tombstone prune는 `IRIS_SNAPSHOT_MISSING_TOMBSTONE_TTL_MS`를 설정했을 때만 활성화됩니다.
- 기본값으로 image reply 준비 시 media scan broadcast를 수행합니다. 필요 시 `IRIS_IMAGE_MEDIA_SCAN=0`으로 비활성화할 수 있습니다.
- image cleanup 주기는 `IRIS_IMAGE_DELETE_INTERVAL_MS`, 보관 기간은 `IRIS_IMAGE_RETENTION_MS`로 조정할 수 있으며 기본값은 각각 1시간, 1일입니다.

- 현재 비게임 webhook consumer inventory:
  - `default` -> `hololive-kakao-bot-go`
  - `chatbotgo` -> `chat-bot-go-kakao`
  - `settlement` -> `settlement-go`

### 백엔드 통신 형식

- Iris는 webhook 요청 바디에 `route`, `messageId`, `sourceLogId`, `text`, `room`, `sender`, `userId`를 전송합니다.
- `threadId`, `threadScope`는 optional 필드이며, observer 경로에서 thread metadata가 존재하면 webhook payload에 포함됩니다.
- Iris는 헤더 `X-Iris-Route`, `X-Iris-Message-Id`, `X-Iris-Token`을 함께 전송합니다.
- 전송 프로토콜 기본값은 h2c 입니다. 필요 시 `IRIS_WEBHOOK_TRANSPORT=http1`로 HTTP/1.1(및 HTTPS)로 강제할 수 있습니다.
- `http://` cleartext webhook는 기본적으로 loopback 주소만 허용됩니다. 명시적으로 private overlay cleartext를 열려면 `IRIS_WEBHOOK_TRANSPORT_SECURITY_MODE=PRIVATE_OVERLAY_HTTP_ALLOWED`를 사용하세요.
- 호환 목적의 기존 `IRIS_ALLOW_CLEARTEXT_HTTP=1`도 여전히 `PRIVATE_OVERLAY_HTTP_ALLOWED`로 해석됩니다.
- 현재 route 기본값은 일반 webhook command가 `default`, `!질문`/`!이미지`/`!그림`/`!리셋`/`!관리자`/`!한강`이 `chatbotgo`, `!정산`/`!정산완료`가 `settlement` 입니다.
- `command_route_prefixes`, `image_message_type_routes` 설정으로 route 규칙을 override할 수 있습니다.

### 서버 준비(요약)
- 내부 네트워크에서 h2c POST를 수신할 `/webhook/...` endpoint 구현 필요
- Iris는 `X-Iris-Message-Id` 헤더로 idempotency 식별자를 전달합니다.
- 내부 네트워크 신뢰 기준에서는 h2c를 우선 사용할 수 있고, 필요 시 TLS/mTLS 구성을 추가할 수 있습니다.

### webhook 주소 정책

- webhook URL은 **Iris가 실제로 접속 가능한 주소**여야 합니다.
- `127.0.0.1` 또는 `localhost`는 **Iris 자신(안드로이드/Redroid 내부)** 을 가리키므로, webhook 서버가 같은 프로세스/컨테이너 안에 있지 않다면 사용하면 안 됩니다.
- 현재 운영 기준 예시는 `http://100.100.1.3:30001/webhook/iris` 처럼 **Redroid/Iris가 실제로 도달 가능한 내부 주소**입니다.
- k8s/도커/브리지 환경에서는 “서버가 bind한 주소”가 아니라, **Iris 네트워크 기준으로 도달 가능한 host IP / container IP / gateway IP / NodePort 주소**를 사용하세요.
- 특히 `127.0.0.1` bind 포트와 bridge/container IP는 다릅니다. 서버가 호스트 `127.0.0.1`에 떠 있어도 Iris가 같은 `127.0.0.1`로 접근할 수 있다는 뜻은 아닙니다.

## HTTP API 엔드포인트

Iris는 기본적으로 HTTP 프로토콜을 통해서도 정보를 주고 받을 수 있습니다.

메서드 요약:
- `GET /health`, `GET /ready`
- `GET /config`
- `GET /reply-status/{requestId}`
- `GET /rooms`, `GET /rooms/{chatId}/members`, `GET /rooms/{chatId}/info`, `GET /rooms/{chatId}/stats`, `GET /rooms/{chatId}/members/{userId}/activity`, `GET /rooms/{chatId}/threads`, `GET /events/stream`
- `GET /diagnostics/bridge`, `GET /diagnostics/chatroom-fields/{chatId}`
- `POST /config/{name}`, `POST /reply`, `POST /query/room-summary`, `POST /query/member-stats`, `POST /query/recent-threads`

JSON body가 필요한 요청은 `Content-Type: application/json`으로 보내야 합니다.

보호 API 인증:
- 모든 보호 API 호출은 `X-Iris-Timestamp`, `X-Iris-Nonce`, `X-Iris-Signature`를 함께 보내야 합니다.
- signature canonical string은 `METHOD + "\n" + PATH_WITH_NORMALIZED_QUERY + "\n" + TIMESTAMP + "\n" + NONCE + "\n" + SHA256(body)` 입니다.
- `/config` 계열은 `inboundSigningSecret`으로, `/reply`/`/reply-status`/`/rooms*`/`/events/stream`/`/query/*`/`/diagnostics/*`는 `botControlToken`으로 서명합니다.
- 해당 secret가 비어 있으면 보호 API는 `503 service unavailable`, 서명이 맞지 않으면 `401 unauthorized`를 반환합니다.

**보호 API 호출 예시 (bash):**

```bash
# 보호 API 호출 예시 (bash)
BOT_CONTROL_TOKEN="<botControlToken>"
HOST="http://[DEVICE_IP]:3000"
METHOD="POST"
PATH_URI="/reply"
BODY='{"type":"text","room":"1234567890","data":"테스트"}'

TIMESTAMP=$(date +%s%3N)
NONCE=$(openssl rand -hex 16)
BODY_HASH=$(printf '%s' "$BODY" | openssl dgst -sha256 -hex | awk '{print $2}')
CANONICAL=$(printf '%s\n%s\n%s\n%s\n%s' "$METHOD" "$PATH_URI" "$TIMESTAMP" "$NONCE" "$BODY_HASH")
SIGNATURE=$(printf '%s' "$CANONICAL" | openssl dgst -sha256 -hmac "$BOT_CONTROL_TOKEN" -hex | awk '{print $2}')

curl -X POST "$HOST$PATH_URI" \
  -H "Content-Type: application/json" \
  -H "X-Iris-Timestamp: $TIMESTAMP" \
  -H "X-Iris-Nonce: $NONCE" \
  -H "X-Iris-Signature: $SIGNATURE" \
  -d "$BODY"
```

*   **`/health`**: liveness 확인
*   **`/ready`**: readiness 확인

*   **`/reply`**: 카카오톡 채팅방에 메시지 또는 사진을 전송 큐에 등록합니다.

    현재 image reply는 JSON base64가 아니라 `multipart/form-data` + signed metadata manifest 경로를 사용합니다.
    metadata는 먼저 와야 하고, 각 image part는 digest/length/content-type/known-image format 검증을 통과해야 합니다.
    core 전송 경계는 handle-only이며, bytes 입력은 edge adapter에서만 handle로 변환됩니다.

    **요청 본문 (JSON):**

    ```json
    {
      "type": "text",  // 또는 "markdown"
      "room": "[CHAT_ID]",  // 채팅방 ID (숫자 문자열)
      "data": "[MESSAGE_TEXT]"
    }
    ```

    **예시 (텍스트 메시지):**

    ```bash
    # 인증 헤더는 위 "보호 API 호출 예시" 참조
    curl -X POST \
      -H "Content-Type: application/json" \
      -H "X-Iris-Timestamp: $TIMESTAMP" \
      -H "X-Iris-Nonce: $NONCE" \
      -H "X-Iris-Signature: $SIGNATURE" \
      -d '{"type": "text", "room": "1234567890", "data": "메시지 전송!"}' \
      http://[YOUR_DEVICE_IP]:3000/reply
    ```

    `/reply`는 `botControlToken`으로 보호됩니다. 비어 있으면 `503 service unavailable`,
    서명이 일치하지 않으면 `401 unauthorized`를 반환합니다.

    요청이 유효하고 메모리 큐 등록에 성공하면 `202 Accepted`를 반환합니다.
    이는 **실제 카카오 전송 성공**이 아니라 **best-effort 비동기 전송 큐 등록 성공**을 의미합니다.

    `threadId`는 숫자 문자열이어야 하며 `text`, `image`, `image_multiple` 타입에서 지원합니다.
    text reply는 room-level일 때 기존 `NotificationActionService`를 사용하고, in-thread일 때는 Kakao share/graft 경로를 사용합니다.
    image/image_multiple도 별도 alias 없이 `/reply`에서 같은 native bridge 파이프라인을 사용합니다.
    현재 image reply 실측 기준 `threadScope=2`는 thread-only, `threadScope=3`은 thread + room 같이 보내기입니다.

    markdown reply도 `type="markdown"`으로 같은 `/reply` endpoint를 사용합니다.
    현재는 `/reply`의 in-thread text와 markdown이 같은 share/graft 경로를 사용합니다.
    thread reply가 필요할 때는 Iris extras와 LSPosed/Xposed hook이 `ChatSendingLogRequest$a.u(...)` 단계에서 `threadId/threadScope`를 graft합니다.

    **요청 본문 (JSON):**

    ```json
    {
      "type": "markdown",
      "room": "[CHAT_ID]",
      "data": "**안녕!**",
      "threadId": "3805486995143352321",
      "threadScope": 2
    }
    ```

    **예시:**

    ```bash
    # 인증 헤더는 위 "보호 API 호출 예시" 참조
    curl -X POST \
      -H "Content-Type: application/json" \
      -H "X-Iris-Timestamp: $TIMESTAMP" \
      -H "X-Iris-Nonce: $NONCE" \
      -H "X-Iris-Signature: $SIGNATURE" \
      -d '{"type": "markdown", "room": "1234567890", "data": "**안녕!**"}' \
      http://[YOUR_DEVICE_IP]:3000/reply
    ```

    제한 사항:

    - markdown only
    - `threadId`가 있으면 `threadScope`는 생략 가능하며, 생략 시 `2`로 처리
    - 실측 기준 `threadScope=2`는 thread-only, `threadScope=3`은 thread + room 같이 보내기
    - 요청이 유효하고 큐 등록에 성공하면 `202 Accepted`를 반환
    - 실제 카카오 UI 렌더링은 클라이언트 버전과 본문 문법에 따라 달라질 수 있음

*   **`/query/room-summary`**, **`/query/member-stats`**, **`/query/recent-threads`**:
    allowlisted read API만 제공합니다. raw SQL을 받는 `/query` endpoint는 제거됐습니다.

    **예시 (`/query/recent-threads`):**

    ```bash
    # 인증 헤더는 위 "보호 API 호출 예시" 참조
    curl -X POST \
      -H "Content-Type: application/json" \
      -H "X-Iris-Timestamp: $TIMESTAMP" \
      -H "X-Iris-Nonce: $NONCE" \
      -H "X-Iris-Signature: $SIGNATURE" \
      -d '{"chatId":12345}' \
      http://[YOUR_DEVICE_IP]:3000/query/recent-threads
    ```

### 설정 API

*   **`/config` (GET)**: 현재 구성 조회
    - 응답은 `user`, `applied`, `discovered`, `pending_restart`를 함께 반환합니다.
    - `user`는 persisted 설정, `applied`는 현재 런타임에 반영된 설정, `discovered`는 `bot_id` 같은 런타임 발견값입니다.
*   **`/config/endpoint` (POST)**: webhook 엔드포인트 업데이트
    - body의 `route`를 비우면 기본 route(`default`)를 갱신합니다.
    - 예: `{"route":"chatbotgo","endpoint":"http://100.100.1.3:31001/webhook/iris"}`
*   **`/config/dbrate` (POST)**: DB 폴링 속도 업데이트
*   **`/config/sendrate` (POST)**: 메시지 전송 속도 업데이트
*   **`/config/botport` (POST)**: HTTP 서버 포트 업데이트 (`requiresRestart=true`)

`/config/botport` 업데이트는 저장된 `user`에는 즉시 반영되지만,
실제 서버가 바인드한 `applied.bot_http_port`는 프로세스 재시작 전까지 유지됩니다.

`POST /config/{name}` 응답은 다음 필드를 포함한 `ConfigUpdateResponse`를 반환합니다:
`success`, `name`, `persisted`, `applied`, `requiresRestart`, `user`, `runtimeApplied`, `discovered`, `pending_restart`.

## 주요 변경 사항

- Legacy bridge 제거
- H2cDispatcher를 앱 내부 비동기 큐 + 코루틴 워커 기반 전송기로 단순화
- route별 webhook 맵과 `X-Iris-Message-Id` 기준으로 전송 경로를 통일
- 보호 API를 role-aware signed request HMAC 계층으로 정리
- DB polling과 webhook delivery를 durable checkpoint + SQLite-backed outbox로 분리
- route별 전송은 room partition worker로 서로의 backlog를 격리
- config 로드/저장 및 이미지 준비 경로의 불필요한 churn을 줄임
- `/reply`는 전송 성공이 아니라 in-memory queue admission 성공 시 `202 Accepted`를 반환
- reply 이미지는 Kakao 공유 루트가 아닌 Iris 전용 하위 디렉터리에서만 정리
- `/query`는 제한된 read-only 쿼리만 허용하고 typed `columns`/`rows` 응답을 반환
- image reply의 `threadId`/`threadScope` 지원 — LSPosed bridge 기반 thread image send
- `callingPackageName` 기본값을 `com.kakao.talk`으로 고정하여 외부 발송 흔적 제거
- `AppRuntime` 도입 — lifecycle orchestrator로 컴포넌트 시작/종료 순서를 일원화
- `KakaoDbRuntime` 도입 — DB connection 추상화 계층
- `KakaoChatLogReader` / `KakaoIdentityReader` 도입 — 조회 계층을 읽기 용도별로 분리
- raw `/query` 제거 — allowlisted read API만 유지
- `IrisMetadataStore` 도입 — iris.db 기반 내부 메타데이터 저장소
- `CheckpointStore` 도입 — checkpoint 파일 관리를 전용 계층으로 분리
- `BridgeHealthCache` 도입 — bridge health 스냅샷을 주기적으로 갱신하는 캐시
- `RequestAuthenticator` 도입 — HMAC 서명 인증 로직을 서버에서 분리

---

## Troubleshooting

### webhook queue 동작

- 현재 구현은 SQLite WAL-backed outbox와 checkpoint를 사용하며 restart 후에도 in-flight/pending delivery를 재개합니다.
- route별 worker는 코루틴으로 동작하며 route 안에서는 room partition 기준으로 순서를 유지합니다.
- 재시도는 outbox 상태에 기록되고, 한 항목은 최대 6회까지만 시도한 뒤 dead-letter 상태로 전환됩니다.
- 현재 재시도 간격은 대략 `1s + 2s + 4s + 8s + 16s (+ jitter)`입니다.
- 개별 HTTP 요청 timeout 상한은 현재 `30s`입니다.
- outbox 적재가 끝나면 checkpoint가 전진하므로, 개별 webhook 실패가 polling 진행 자체를 막지는 않습니다.
- 종료 시에는 polling을 멈추고, outbox store는 그대로 남겨 다음 기동에서 이어집니다.
- `/reply`의 `202 Accepted`는 여전히 memory reply queue admission 성공을 의미하지만, observer -> webhook 경로는 durable outbox로 분리되어 있습니다.

## 크레딧

*   **SendMsg 및 초기 컨셉:** `ye-seola/go-kdb`의 작업을 기반으로 합니다.
*   **카카오톡 복호화 로직:** `jiru/kakaodecrypt`의 복호화 메서드를 사용합니다.

## 면책 조항

이 프로젝트는 교육 및 연구 목적으로만 제공됩니다. 개발자는 이 소프트웨어의 오용이나 손해에 대하여 책임을 지지 않습니다. 사용자의 책임 하에 사용하시고, 관련 법률 및 서비스 이용약관을 준수하시기 바랍니다.
