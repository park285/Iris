# Iris - 안드로이드 네이티브 DB기반 봇 프레임워크

이 프로젝트는 카카오톡 안드로이드 앱의 데이터베이스와 연동하여 HTTP webhook/h2c 기반 채팅 봇을 작성할 수 있는 환경을 제공합니다.

**프로젝트 상태:** 베타

## 현재 상태 요약

- headless Redroid 런타임 기준으로 운영 중입니다.
- outbound webhook 전송은 `H2cDispatcher`가 담당합니다.
- 보호 API(`/config`, `/reply`, `/query`)는 `X-Bot-Token` 기반 fail-closed 정책을 사용합니다.
- 신규 webhook admission은 in-memory bounded queue에 등록됩니다.
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

    `/data/local/tmp/config.json` (또는 `IRIS_CONFIG_PATH`)에 설정 파일을 배치하세요.

---

## webhook/h2c 기반 봇 통합 가이드 (내부 통신 권장)

### 아키텍처 개요

Iris는 HTTP/2 cleartext(h2c) 우선 설정의 webhook 호출로 봇과 직접 통신할 수 있습니다:

```
카카오톡 메시지
    ↓
Iris (H2cDispatcher)
    → HTTP POST (h2c 우선)
Bot Webhook
    ↓
카카오톡 응답 전송 (Iris)
```

### Iris 설정

`/data/local/tmp/config.json` (또는 `IRIS_CONFIG_PATH`)에서 webhook을 설정합니다.

```json
{
  "botName": "Iris",
  "botHttpPort": 3000,
  "endpoint": "http://<DEFAULT_REACHABLE_HOST_IP>:30001/webhook/iris",
  "webhooks": {
    "hololive": "http://<HOLOLIVE_HOST_IP>:30001/webhook/iris",
    "chatbotgo": "http://<CHATBOTGO_HOST_IP>:31001/webhook/iris"
  },
  "webhookToken": "<OPTIONAL_SHARED_TOKEN>",
  "botToken": "<REQUIRED_SHARED_TOKEN>",
  "dbPollingRate": 100,
  "messageSendRate": 50
}
```

설명:
- `endpoint`: route별 설정이 없는 경우 사용하는 기본 webhook endpoint
- `webhooks.<route>`: route별 override endpoint. 예: `hololive`, `chatbotgo`
- `webhooks.hololive`가 있으면 `endpoint`보다 우선합니다.
- `webhookToken`: 설정 시 `X-Iris-Token` 헤더로 전달되는 공유 토큰
- `botToken`: `/config`, `/reply`, `/query` 보호 API 호출 시 `X-Bot-Token` 헤더와 일치해야 하는 공유 토큰
- `webhookToken`, `botToken`이 config에 비어 있으면 각각 `IRIS_WEBHOOK_TOKEN`, `IRIS_BOT_TOKEN` 환경변수로 폴백합니다.
- `botToken`이 비어 있으면 보호 API는 열리지 않으며 `503 service unavailable`으로 거부됩니다.
- 기본값으로 image reply 준비 시 media scan broadcast를 수행합니다. 필요 시 `IRIS_IMAGE_MEDIA_SCAN=0`으로 비활성화할 수 있습니다.
- image cleanup 주기는 `IRIS_IMAGE_DELETE_INTERVAL_MS`, 보관 기간은 `IRIS_IMAGE_RETENTION_MS`로 조정할 수 있으며 기본값은 각각 1시간, 1일입니다.

### 백엔드 통신 형식

- Iris는 webhook 요청 바디에 `route`, `messageId`, `sourceLogId`, `text`, `room`, `sender`, `userId`를 전송합니다.
- `threadId`, `threadScope`는 optional 필드이며, observer 경로에서 thread metadata가 존재하면 webhook payload에 포함됩니다.
- Iris는 헤더 `X-Iris-Route`, `X-Iris-Message-Id`, `X-Iris-Token`을 함께 전송합니다.
- 전송 프로토콜 기본값은 h2c 입니다. 필요 시 `IRIS_WEBHOOK_TRANSPORT=http1`로 HTTP/1.1(및 HTTPS)로 강제할 수 있습니다.
- 현재 route 기본값은 일반 webhook command는 `hololive`, `!질문`/`!리셋`/`!관리자`는 `chatbotgo` 입니다.

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
- `POST /config/{name}`, `POST /reply`, `POST /query`

JSON body가 필요한 요청은 `Content-Type: application/json`으로 보내야 합니다.

*   **`/health`**: liveness 확인
*   **`/ready`**: readiness 확인

*   **`/reply`**: 카카오톡 채팅방에 메시지 또는 사진을 전송 큐에 등록합니다.

    **요청 본문 (JSON):**

    ```json
    {
      "type": "text",  // 또는 "image", "image_multiple"
      "room": "[CHAT_ID]",  // 채팅방 ID (숫자 문자열)
      "data": "[MESSAGE_TEXT]"
    }
    ```

    **예시 (텍스트 메시지):**

    ```bash
    curl -X POST \
      -H "Content-Type: application/json" \
      -H "X-Bot-Token: [IRIS_BOT_TOKEN]" \
      -d '{"type": "text", "room": "1234567890", "data": "메시지 전송!"}' \
      http://[YOUR_DEVICE_IP]:3000/reply
    ```

    `botToken`은 보호 API에 필수입니다. 비어 있으면 `503 service unavailable`,
    값이 다르면 `401 unauthorized`를 반환합니다.

    요청이 유효하고 메모리 큐 등록에 성공하면 `202 Accepted`를 반환합니다.
    이는 **실제 카카오 전송 성공**이 아니라 **best-effort 비동기 전송 큐 등록 성공**을 의미합니다.

    `threadId`는 숫자 문자열이어야 하며 현재 `text` reply에서만 지원합니다.
    `image`, `image_multiple`과 함께 보내면 `400 bad request`를 반환합니다.

*   **`/query`**: 카카오톡 데이터베이스에 SQL 쿼리를 실행합니다.

    현재는 `SELECT`, `WITH`, `PRAGMA`로 시작하는 read-only 쿼리만 허용합니다.
    요청 본문은 `query`와 선택형 `bind` 배열을 받을 수 있습니다.
    응답에는 `rowCount`와 `data`가 함께 반환됩니다.
    현재 최대 응답 행 수는 `500`이며, 이를 넘기면 `400 bad request`를 반환합니다.

    **예시:**

    ```bash
    curl -X POST \
      -H "Content-Type: application/json" \
      -H "X-Bot-Token: [IRIS_BOT_TOKEN]" \
      -d '{"query": "SELECT _id, chat_id, message FROM chat_logs WHERE chat_id = ? ORDER BY _id DESC LIMIT 5", "bind": ["12345"]}' \
      http://[YOUR_DEVICE_IP]:3000/query
    ```

    `botToken`은 보호 API에 필수입니다. 비어 있으면 `503 service unavailable`,
    값이 다르면 `401 unauthorized`를 반환합니다.

### 설정 API

*   **`/config` (GET)**: 현재 구성 조회
    - 응답은 `snapshot`, `effective`, `pendingRestart`를 함께 반환합니다.
    - `snapshot`과 `effective`에는 route별 endpoint 맵 `webhooks`도 포함됩니다.
    - 하위 호환용 top-level 필드는 현재 적용 중인 `effective` 값을 반영합니다.
*   **`/config/endpoint` (POST)**: webhook 엔드포인트 업데이트
    - body의 `route`를 비우면 기본 route(`hololive`)를 갱신합니다.
    - 예: `{"route":"chatbotgo","endpoint":"http://100.100.1.3:31001/webhook/iris"}`
*   **`/config/dbrate` (POST)**: DB 폴링 속도 업데이트
*   **`/config/sendrate` (POST)**: 메시지 전송 속도 업데이트
*   **`/config/botport` (POST)**: HTTP 서버 포트 업데이트 (`requiresRestart=true`)

`/config/botport` 업데이트는 저장된 `snapshot`에는 즉시 반영되지만,
실제 서버가 바인드한 `effective.botHttpPort`는 프로세스 재시작 전까지 유지됩니다.

`/config`, `/reply`, `/query`는 모두 보호 API입니다.
`botToken`이 비어 있으면 `503 service unavailable`, 값이 다르면 `401 unauthorized`를 반환합니다.

## 주요 변경 사항 (v2.0)

- Legacy bridge 제거
- H2cDispatcher를 앱 내부 비동기 큐 + 코루틴 워커 기반 전송기로 단순화
- route별 webhook 맵과 `X-Iris-Message-Id` 기준으로 전송 경로를 통일
- 보호 API를 `X-Bot-Token` 기반 fail-closed 모델로 고정
- 신규 webhook admission은 메모리 bounded queue에만 등록
- route별 전송은 각 봇별 전용 coroutine worker로 서로의 backlog를 격리
- config 로드/저장 및 이미지 준비 경로의 불필요한 churn을 줄임
- `/reply`는 전송 성공이 아니라 in-memory queue admission 성공 시 `202 Accepted`를 반환
- reply 이미지는 Kakao 공유 루트가 아닌 Iris 전용 하위 디렉터리에서만 정리
- `/query`는 `SELECT`/`WITH`/`PRAGMA`만 허용하며 응답에 `rowCount`를 포함

---

## Troubleshooting

### webhook queue 동작

- 현재 구현은 디스크 journal이나 restart recovery 없이 메모리 bounded queue만 사용합니다.
- route별 worker는 코루틴으로 동작하며 route당 strict FIFO 단일 worker로 처리합니다.
- 재시도는 worker 내부에서만 backoff로 처리하고, 한 항목은 최대 6회까지만 시도한 뒤 drop 됩니다.
- 현재 재시도 간격은 대략 `1s + 2s + 4s + 8s + 16s (+ jitter)`입니다.
- 개별 HTTP 요청 timeout 상한은 현재 `30s`입니다.
- route queue가 포화되면 신규 admission은 즉시 `RETRY_LATER`를 반환하고, upstream이 같은 source offset에서 다시 시도하는 방식으로 순서를 유지합니다.
- 종료 시에는 새 admission을 막고, 이미 channel에 들어간 queued delivery와 진행 중인 전송/재시도를 최대 `10s`까지 drain한 뒤 남은 작업을 취소합니다.
- 프로세스 재시작이나 강제 종료가 발생하면 메모리 큐에 남아 있던 pending delivery는 복구되지 않습니다.
- 따라서 `202 Accepted`는 durable persistence가 아니라 현재 프로세스의 비동기 큐 등록 성공을 의미합니다.

## Credits

*   **SendMsg & Initial Concept:** Based on the work of `ye-seola/go-kdb`.
*   **KakaoTalk Decryption Logic:** Decryption methods from `jiru/kakaodecrypt`.

## Disclaimer

This project is provided for educational and research purposes only. The developers are not responsible for any misuse or damage caused by this software. Use it at your own risk and ensure you comply with all applicable laws and terms of service.
