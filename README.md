# Iris

카카오톡 안드로이드 앱의 SQLite DB를 polling하여 메시지를 감지하고, HTTP webhook으로 외부 봇 서버에 전달하는 headless Android bot framework입니다.
root `app_process` 데몬으로 동작하며, Redroid 등 headless Android 환경에서 실행할 수 있습니다.

**프로젝트 상태:** 베타

## 필요 조건

- 카카오톡이 설치되고 **로그인된** 안드로이드 환경 (Redroid, 에뮬레이터, 실기기)
- 루트 권한 (카카오톡 DB 및 시스템 서비스 접근)
- LSPosed (또는 Xposed 호환 프레임워크) — 이미지 전송 기능 사용 시
- Iris가 도달 가능한 네트워크에서 webhook을 수신할 HTTP 서버

## 빌드

```bash
# Iris 본체
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Bridge 모듈 (이미지 전송용, 선택)
./gradlew :bridge:assembleRelease
# → output/IrisBridge-release.apk
```

릴리스 빌드는 `assembleRelease`를 사용합니다.

## 세팅 가이드

### 1. 기기 준비

카카오톡이 설치되고 로그인된 상태여야 합니다. Iris는 카카오톡 DB를 직접 읽으므로 **카카오톡이 먼저 실행 중**이어야 합니다.

### 2. Iris 설치 및 실행

```bash
# APK를 기기에 push
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/Iris.apk

# 직접 실행 (포그라운드, 로그 확인용)
adb shell su -c 'CLASSPATH=/data/local/tmp/Iris.apk app_process / party.qwer.iris.Main'
```

또는 `iris_control` 스크립트로 관리할 수 있습니다:

```bash
./iris_control install   # APK push (+ SHA-256 verify if checksum file is available)
./iris_control start     # 백그라운드 실행
./iris_control start -v  # 포그라운드 실행 (로그 출력)
./iris_control stop      # 종료
./iris_control status    # 프로세스 상태 확인
```

### 3. 설정 파일

첫 기동 시 `/data/iris/config.json`이 없으면 기본값으로 자동 생성됩니다.
경로는 `IRIS_CONFIG_PATH` 환경변수로 override할 수 있습니다.

```json
{
  "botName": "MyBot",
  "botHttpPort": 3000,
  "webhooks": {
    "default": "http://<BOT_SERVER_IP>:<PORT>/webhook"
  },
  "inboundSigningSecret": "<SECRET>",
  "outboundWebhookToken": "<SECRET>",
  "botControlToken": "<SECRET>",
  "bridgeToken": "<SECRET>",
  "dbPollingRate": 100,
  "messageSendRate": 50
}
```

| 필드 | 필수 | 설명 |
|------|------|------|
| `webhooks` | O | route별 webhook endpoint 맵. `default`가 기본 route |
| `inboundSigningSecret` | O | `/config` API 보호용 HMAC 서명 키 |
| `outboundWebhookToken` | O | outbound webhook 전송 시 `X-Iris-Token` 헤더로 전달 |
| `botControlToken` | O | `/reply`, `/rooms*`, `/events/stream`, `/query/*`, `/diagnostics/*` 보호용 서명 키 |
| `bridgeToken` | 조건부 | app process ↔ LSPosed bridge 간 UDS handshake 토큰. 이미지/마크다운 전송 사용 시 필요하며, `IRIS_REQUIRE_BRIDGE`를 따로 설정하지 않아도 `config.json`의 `bridgeToken` 또는 `IRIS_BRIDGE_TOKEN` fallback 중 하나가 있으면 `/ready`가 bridge readiness를 함께 검사 |
| `botHttpPort` | | HTTP 서버 포트 (기본 `3000`) |
| `dbPollingRate` | | DB polling 주기 ms (기본 `100`) |
| `messageSendRate` | | 메시지 전송 간격 ms (기본 `50`) |

역할별 secret이 비어 있으면 해당 보호 API는 `503 Service Unavailable`로 거부됩니다.

### 4. Bridge 설치 (이미지 전송용)

이미지 전송(`image`, `image_multiple` 타입)과 마크다운 전송은 LSPosed bridge 모듈이 필요합니다.
텍스트 전송만 사용한다면 bridge 없이 동작합니다.
`IRIS_REQUIRE_BRIDGE`를 명시하지 않은 경우에도 `config.json`의 `bridgeToken` 또는 `IRIS_BRIDGE_TOKEN` fallback이 설정된 배포에서는 `/ready`가 bridge token과 bridge health를 필수로 검사합니다.
텍스트 전용처럼 둘 다 없으면 `/ready`는 bridge를 필수로 보지 않습니다.
`IRIS_REQUIRE_BRIDGE=true|1|on` 또는 `IRIS_REQUIRE_BRIDGE=false|0|off`를 명시하면 그 값을 그대로 따릅니다.
그 외 값은 잘못된 설정으로 경고 로그를 남기고 자동 판정으로 되돌아갑니다.

**설치:**

1. `./gradlew :bridge:assembleRelease`로 빌드
2. `output/IrisBridge-release.apk`를 기기에 설치 (일반 앱 설치)
3. LSPosed Manager에서 IrisBridge 모듈 활성화
4. **scope를 `com.kakao.talk`(카카오톡)에만 적용**
5. 카카오톡 강제 종료 후 재시작 (hook 적용을 위해)

**bridge 토큰:**

Iris 본체와 bridge는 같은 `bridgeToken`을 공유해야 합니다.
bridge는 `config.json`의 `bridgeToken` 필드를 읽습니다.
`config.json`에 접근할 수 없는 환경이면 `IRIS_BRIDGE_TOKEN` 환경변수를 fallback으로 사용합니다.

**통신 방식:**

bridge는 Android abstract namespace UDS 소켓(`@iris-image-bridge`)으로 Iris 본체와 통신합니다. 네트워크 설정은 필요 없습니다.

### 5. 정상 기동 확인

**로그:**

```
Message sender thread started
DBObserver started
Iris Server started
```

**HTTP:**

```bash
# liveness (인증 불필요)
curl http://<DEVICE_IP>:3000/health
# → {"status":"ok"}

# readiness (모든 필수 설정과 컴포넌트가 준비되었을 때만 200)
curl http://<DEVICE_IP>:3000/ready
# → {"status":"ready"} 또는 503 + {"message":"not ready"}
```

기본 설정에서는 `/ready`가 503을 반환해도 응답 본문에는 내부 설정 실패 사유를 자세히 노출하지 않고 `{"message":"not ready"}`만 반환합니다.
개발/디버깅 중에만 `IRIS_READY_VERBOSE=true|1|on`으로 raw reason 노출을 켤 수 있습니다.
기본값은 실제 bridge token 해석 계약 기준입니다. `config.json`의 `bridgeToken` 또는 `IRIS_BRIDGE_TOKEN` fallback이 있으면 bridge readiness를 필수로 보고, 둘 다 없으면 선택 사항으로 봅니다.
이 기본값을 덮어쓰려면 `IRIS_REQUIRE_BRIDGE=true|1|on` 또는 `IRIS_REQUIRE_BRIDGE=false|0|off`를 설정하세요. 다른 값은 경고 후 자동 판정으로 처리됩니다.

### webhook 주소 정책

webhook URL은 **Iris(Android 기기 내부)에서 실제로 접속 가능한 주소**여야 합니다.

- `127.0.0.1`은 Iris 자신을 가리킵니다. webhook 서버가 다른 머신에 있다면 사용할 수 없습니다.
- 컨테이너/브리지 환경에서는 서버의 bind 주소가 아니라 **Iris 네트워크 기준으로 도달 가능한 IP**를 사용하세요.
- 호스트의 `127.0.0.1:8080`에 서버가 떠 있어도, 컨테이너 안의 Iris가 같은 주소로 접근할 수 있다는 뜻이 아닙니다.
- `default` endpoint는 fallback입니다. route별 endpoint만 설정된 구성도 허용되며, 이 경우 해당 route에 대해서만 dispatch가 진행됩니다.

### 환경변수

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `IRIS_CONFIG_PATH` | `/data/iris/config.json` | 설정 파일 경로 |
| `IRIS_DATA_DIR` | `/data/iris` | 데이터/로그 디렉터리 |
| `IRIS_BIND_HOST` | `127.0.0.1` | HTTP 서버 bind 주소 |
| `IRIS_HTTP_WORKER_THREADS` | `2` | Netty worker 스레드 수 |
| `IRIS_WEBHOOK_TRANSPORT` | `h2c` | webhook 전송 프로토콜 (`h2c` 또는 `http1`) |
| `IRIS_WEBHOOK_TRANSPORT_SECURITY_MODE` | — | `PRIVATE_OVERLAY_HTTP_ALLOWED`로 설정 시 private network cleartext 허용 |
| `IRIS_BRIDGE_TOKEN` | — | bridge 토큰 (config.json fallback) |
| `IRIS_REQUIRE_BRIDGE` | 자동 | `true|1|on`이면 필수, `false|0|off`면 선택. 미설정 또는 잘못된 값이면 자동 판정으로 돌아가며, 자동 모드에서는 `bridgeToken` 또는 `IRIS_BRIDGE_TOKEN`이 있으면 필수 |
| `IRIS_READY_VERBOSE` | `false` | `true|1|on`이면 `/ready`가 raw failure reason을 응답에 노출. 기본값은 `not ready`만 반환 |
| `IRIS_BRIDGE_SECURITY_MODE` | `production` | `development`로 설정 시 보안 검사 완화 |
| `IRIS_IMAGE_MEDIA_SCAN` | `1` | image reply 시 media scan broadcast 여부 (`0`으로 비활성화) |
| `IRIS_IMAGE_DELETE_INTERVAL_MS` | `3600000` | image cleanup 주기 (ms) |
| `IRIS_IMAGE_RETENTION_MS` | `86400000` | image 보관 기간 (ms) |
| `IRIS_LOG_LEVEL` | — | `NONE`으로 설정 시 무로그 |
| `IRIS_DISABLE_HTTP` | — | `1`로 설정 시 HTTP 서버 미기동 |

## Webhook 수신 연동

Iris는 카카오톡 DB에서 메시지를 감지하면 설정된 webhook endpoint로 HTTP POST를 전송합니다.

### 요청 형식

**헤더:**

| 헤더 | 설명 |
|------|------|
| `X-Iris-Route` | 메시지가 매칭된 route 이름 |
| `X-Iris-Message-Id` | idempotency 식별자 |
| `X-Iris-Token` | `outboundWebhookToken` 값 |

**본문:**

| 필드 | 타입 | 설명 |
|------|------|------|
| `route` | string | route 이름 |
| `messageId` | string | 메시지 고유 ID |
| `sourceLogId` | string | 원본 채팅 로그 ID |
| `text` | string | 메시지 본문 |
| `room` | string | 채팅방 ID |
| `sender` | string | 발신자 이름 |
| `userId` | string | 발신자 ID |
| `threadId` | string? | thread ID (optional) |
| `threadScope` | int? | thread scope (optional) |

### 전송 프로토콜

기본값은 h2c (HTTP/2 cleartext)입니다. `IRIS_WEBHOOK_TRANSPORT=http1`로 HTTP/1.1을 강제할 수 있습니다.
`http://` cleartext webhook은 기본적으로 loopback만 허용됩니다. private network에서 사용하려면 `IRIS_WEBHOOK_TRANSPORT_SECURITY_MODE=PRIVATE_OVERLAY_HTTP_ALLOWED`를 설정하세요.

### 전송 보장

- SQLite WAL-backed durable outbox로 전송을 관리하며, restart 후에도 pending delivery를 재개합니다.
- route별 worker가 room partition 기준으로 순서를 유지합니다.
- 실패 시 최대 6회 재시도 (지수 백오프 `1s → 2s → 4s → 8s → 16s` + jitter), 이후 dead-letter 처리됩니다.
- 개별 HTTP 요청 timeout은 30초입니다.

### 명령어 라우팅

`command_route_prefixes`와 `image_message_type_routes` 설정으로 특정 명령어 패턴을 다른 webhook route로 분기할 수 있습니다. 미설정 시 모든 메시지가 `default` route로 전송됩니다.

## Reply (메시지 보내기)

Iris HTTP API를 통해 카카오톡 채팅방에 메시지를 보낼 수 있습니다.
모든 reply 요청은 보호 API이며, HMAC 서명 인증이 필요합니다 (아래 [인증](#인증) 참조).

`/reply`가 `202 Accepted`를 반환하면 **큐 등록 성공**을 의미합니다. 실제 카카오톡 전송 완료가 아닙니다.
거절 시: `400` (잘못된 요청), `413` (페이로드 초과), `429` (큐 포화), `503` (종료 중/secret 미설정).

### 텍스트 메시지

```bash
curl -X POST "http://<DEVICE_IP>:3000/reply" \
  -H "Content-Type: application/json" \
  -H "X-Iris-Timestamp: $TIMESTAMP" \
  -H "X-Iris-Nonce: $NONCE" \
  -H "X-Iris-Signature: $SIGNATURE" \
  -d '{"type":"text","room":"<chatId>","data":"보낼 메시지"}'
```

### 마크다운 메시지

```bash
curl -X POST "http://<DEVICE_IP>:3000/reply" \
  -d '{"type":"markdown","room":"<chatId>","data":"**굵게** _기울임_"}'
```

마크다운 전송은 bridge 모듈이 필요합니다.

### Thread reply

`threadId`를 추가하면 특정 스레드에 답장합니다:

```json
{
  "type": "text",
  "room": "<chatId>",
  "data": "스레드 답장",
  "threadId": "<threadId>",
  "threadScope": 2
}
```

| threadScope | 동작 |
|-------------|------|
| `2` (기본) | thread 내에서만 전송 |
| `3` | thread + room 동시 전송 |

`threadId`가 있고 `threadScope`를 생략하면 `2`로 처리됩니다.

### 이미지 메시지

이미지는 `multipart/form-data`로 전송합니다. bridge 모듈이 필요합니다.

**Part 구성 (순서 중요):**

1. `metadata` part (JSON):

```json
{
  "type": "image",
  "room": "<chatId>",
  "images": [
    { "index": 0, "sha256Hex": "<sha256>", "byteLength": 204800, "contentType": "image/jpeg" }
  ]
}
```

2. `image` part (바이너리) — `images` 배열 순서대로

복수 이미지는 `"type": "image_multiple"`을 사용하고 `images` 배열과 `image` part를 순서대로 추가합니다.

**제한:**

| 항목 | 한도 |
|------|------|
| 이미지 수 | 최대 8장 |
| 단일 이미지 | 20 MB |
| 전체 합계 | 30 MB |
| 허용 타입 | `image/jpeg`, `image/png`, `image/webp`, `image/gif` |

### 전송 상태 추적

`/reply` 응답에 포함된 `requestId`로 전송 상태를 조회할 수 있습니다:

```bash
curl "http://<DEVICE_IP>:3000/reply-status/<requestId>" \
  -H "X-Iris-Timestamp: $TIMESTAMP" \
  -H "X-Iris-Nonce: $NONCE" \
  -H "X-Iris-Signature: $SIGNATURE"
```

**응답:**

```json
{
  "requestId": "reply-550e8400-...",
  "state": "handoff_completed",
  "updatedAtEpochMs": 1711234567890,
  "detail": null
}
```

| state | 의미 |
|-------|------|
| `queued` | 큐 진입, 처리 대기 |
| `preparing` | 이미지 파일 준비 중 |
| `prepared` | 준비 완료 |
| `sending` | 카카오톡으로 전달 시도 중 |
| `handoff_completed` | 카카오톡에 핸드오프 완료 |
| `failed` | 실패 (`detail`에 사유) |

## HTTP API 레퍼런스

### 인증

보호 API는 아래 헤더를 함께 보내야 합니다:

| 헤더 | 설명 |
|------|------|
| `X-Iris-Timestamp` | 요청 시각 (epoch ms) |
| `X-Iris-Nonce` | 1회성 랜덤 값 |
| `X-Iris-Signature` | HMAC-SHA256 서명 |

서명 canonical string:

```
METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + NONCE + "\n" + SHA256(body)
```

- `/config` 계열 → `inboundSigningSecret`으로 서명
- 그 외 보호 API → `botControlToken`으로 서명

**서명 생성 예시 (bash):**

```bash
SECRET="<your-secret>"
METHOD="POST"
PATH_URI="/reply"
BODY='{"type":"text","room":"1234567890","data":"hello"}'

TIMESTAMP=$(date +%s%3N)
NONCE=$(openssl rand -hex 16)
BODY_HASH=$(printf '%s' "$BODY" | openssl dgst -sha256 -hex | awk '{print $2}')
CANONICAL=$(printf '%s\n%s\n%s\n%s\n%s' "$METHOD" "$PATH_URI" "$TIMESTAMP" "$NONCE" "$BODY_HASH")
SIGNATURE=$(printf '%s' "$CANONICAL" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $2}')

curl -X POST "http://<DEVICE_IP>:3000$PATH_URI" \
  -H "Content-Type: application/json" \
  -H "X-Iris-Timestamp: $TIMESTAMP" \
  -H "X-Iris-Nonce: $NONCE" \
  -H "X-Iris-Signature: $SIGNATURE" \
  -d "$BODY"
```

### 엔드포인트 목록

#### Health (인증 불필요)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/health` | liveness 확인 |
| GET | `/ready` | readiness 확인 |

#### Reply

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/reply` | 메시지 전송 큐 등록 |
| GET | `/reply-status/{requestId}` | 전송 상태 조회 |

#### 채팅방 조회

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/rooms` | 채팅방 목록 |
| GET | `/rooms/{chatId}/members` | 멤버 목록 |
| GET | `/rooms/{chatId}/info` | 채팅방 정보 |
| GET | `/rooms/{chatId}/stats` | 채팅방 통계 |
| GET | `/rooms/{chatId}/members/{userId}/activity` | 멤버 활동 |
| GET | `/rooms/{chatId}/threads` | 스레드 목록 |

#### Query

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/query/room-summary` | 채팅방 요약 |
| POST | `/query/member-stats` | 멤버 통계 |
| POST | `/query/recent-threads` | 최근 스레드 |
| POST | `/query/recent-messages` | 최근 메시지 |

`/query/recent-messages` 요청 본문은 `{"chatId":42,"limit":50}` 형식이다. `limit`은 최대 300개로 제한되며, 커서 조회를 위해 선택적으로 `afterId`, `beforeId`, `threadId`를 지정할 수 있다. 커서가 없으면 최신순(`created_at DESC, id DESC`)으로 반환한다. `afterId`는 요약 진행용 다음 구간을 `id ASC`로 반환하고, `beforeId`는 이전 페이지를 `id DESC`로 반환한다. `afterId`와 `beforeId`는 동시에 지정할 수 없다. 다음 클라이언트 연동 작업은 [recent messages pagination guide](RECENT_MESSAGES_PAGINATION.md)를 기준으로 진행한다.

#### 설정

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/config` | 현재 구성 조회 (`user`, `applied`, `discovered`, `pending_restart`) |
| POST | `/config/endpoint` | webhook endpoint 갱신 (`{"route":"...", "endpoint":"..."}`) |
| POST | `/config/dbrate` | DB polling 속도 변경 |
| POST | `/config/sendrate` | 메시지 전송 속도 변경 |
| POST | `/config/botport` | HTTP 포트 변경 (재시작 필요) |

#### 진단

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/diagnostics/bridge` | bridge 상태 |
| GET | `/diagnostics/chatroom-fields/{chatId}` | 채팅방 필드 진단 |

#### SSE

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/events/stream` | Server-Sent Events 스트림 |

## 크레딧

- **SendMsg 및 초기 컨셉:** `ye-seola/go-kdb`
- **카카오톡 복호화 로직:** `jiru/kakaodecrypt`
- **원본 Iris:** `dolidolih/Iris`


## 면책 조항

이 프로젝트는 교육 및 연구 목적으로만 제공됩니다. 개발자는 이 소프트웨어의 오용이나 손해에 대하여 책임을 지지 않습니다. 사용자의 책임 하에 사용하시고, 관련 법률 및 서비스 이용약관을 준수하시기 바랍니다.
