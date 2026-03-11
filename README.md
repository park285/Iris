# Iris - 안드로이드 네이티브 DB기반 봇 프레임워크

이 프로젝트는 카카오톡 안드로이드 앱의 데이터베이스와 연동하여 HTTP webhook/h2c 기반 채팅 봇을 작성할 수 있는 환경을 제공합니다.

**프로젝트 상태:** 베타

## 확장 기능

이 포크 버전은 원본 Iris에 다음 기능이 추가되었습니다:


이 기능들은 원본 Iris 프로젝트에는 없는 확장 구현입니다.

## 시작하기

### 필요 조건

*   **안드로이드 기기:** 이 애플리케이션은 카카오톡이 설치되어 있는 안드로이드 기기에서 실행되도록 설계되었습니다.
*   **루트 권한:** 카카오톡 데이터베이스와 일부 시스템 서비스에 접근하기 위하여 루트 권한이 필요합니다.
*   **백엔드 통신:** 내부 네트워크에서 접근 가능한 webhook/h2c 엔드포인트가 필요합니다.

### 설치

1.  **최신 Iris를 [Releases](https://github.com/park285/Iris/releases)에서 다운로드하세요.**

2.  **파일 복사:**
    adb를 사용하여 Iris apk 파일을 안드로이드 환경에 복사하세요.
    ```bash
    adb push Iris.apk /data/local/tmp
    ```

3.  **apk 파일 실행:**

    iris_control을 실행 가능하게 만드세요.
    ```bash
    chmod +x iris_control
    ```

    실행하려면 iris_control을 사용하세요.
    ```bash
    ./iris_control start
    ```

    iris_control은 install/start/status/stop 명령어를 제공합니다.

4.  **Config 설정:**

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
  "webhooks": {
    "hololive": "http://<REACHABLE_HOST_IP>:30001/webhook/iris"
  },
  "webhookToken": "",
  "botToken": "",
  "dbPollingRate": 100,
  "messageSendRate": 50
}
```

설명:
- `webhooks.hololive`: 기본 webhook endpoint
- `webhookToken`: 설정 시 `X-Iris-Token` 헤더로 전달되는 공유 토큰
- `botToken`: `/reply` 호출 시 `X-Bot-Token` 헤더와 일치해야 하는 공유 토큰

### 백엔드 통신 형식

- Iris는 webhook 요청 바디에 `route`, `messageId`, `sourceLogId`, `text`, `room`, `sender`, `userId`, `threadId`를 함께 전송합니다.
- Iris는 헤더 `X-Iris-Route`, `X-Iris-Message-Id`, `X-Iris-Token`을 함께 전송합니다.
- 전송 프로토콜 기본값은 h2c 입니다. 필요 시 `IRIS_WEBHOOK_TRANSPORT=http1`로 HTTP/1.1(및 HTTPS)로 강제할 수 있습니다.

### 서버 준비(요약)
- 내부 네트워크에서 h2c POST를 수신할 `/webhook/...` endpoint 구현 필요
- Iris는 `X-Iris-Message-Id` 헤더로 idempotency 식별자를 전달합니다.
- 내부 네트워크 신뢰 기준에서는 h2c를 우선 사용할 수 있고, 필요 시 TLS/mTLS 구성을 추가할 수 있습니다.

### webhook 주소 정책

- webhook URL은 **Iris가 실제로 접속 가능한 주소**여야 합니다.
- `127.0.0.1` 또는 `localhost`는 **Iris 자신(안드로이드/Redroid 내부)** 을 가리키므로, webhook 서버가 같은 프로세스/컨테이너 안에 있지 않다면 사용하면 안 됩니다.
- 운영 점검 기준(2026-03-09): Redroid 내부에서 `172.18.0.2:30001`은 도달 가능했고, `172.18.0.1:30001` 및 `127.0.0.1:30001`은 도달 불가였습니다.
- k8s/도커/브리지 환경에서는 “서버가 bind한 주소”가 아니라, **Iris 네트워크 기준으로 도달 가능한 host IP / container IP / gateway IP / NodePort 주소**를 사용하세요.
- 특히 `127.0.0.1` bind 포트와 bridge/container IP는 다릅니다. 서버가 호스트 `127.0.0.1`에 떠 있어도 Iris가 같은 `127.0.0.1`로 접근할 수 있다는 뜻은 아닙니다.

## HTTP API 엔드포인트

Iris는 기본적으로 HTTP 프로토콜을 통해서도 정보를 주고 받을 수 있습니다.

모든 요청은 별도로 명시되지 않는 한 `Content-Type: application/json`과 함께 `POST` 요청으로 보내야 합니다.

*   **`/reply`**: 카카오톡 채팅방에 메시지 또는 사진을 보냅니다.

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

    `botToken`이 비어 있지 않으면 `X-Bot-Token` 헤더가 반드시 일치해야 하며, 불일치 시 `401 unauthorized`를 반환합니다.

*   **`/query`**: 카카오톡 데이터베이스에 SQL 쿼리를 실행합니다.

    **예시:**

    ```bash
    curl -X POST \
      -H "Content-Type: application/json" \
      -H "X-Bot-Token: [IRIS_BOT_TOKEN]" \
      -d '{"query": "SELECT _id, chat_id, message FROM chat_logs ORDER BY _id DESC LIMIT 5"}' \
      http://[YOUR_DEVICE_IP]:3000/query
    ```

    `botToken`이 비어 있지 않으면 `X-Bot-Token` 헤더가 반드시 일치해야 합니다.

*   **`/decrypt`**: 카카오톡 메시지를 복호화합니다.

    `botToken`이 비어 있지 않으면 `X-Bot-Token` 헤더가 반드시 일치해야 합니다.

*   **`/aot` (GET)**: AOT 토큰을 리턴합니다.

    `botToken`이 비어 있지 않으면 `X-Bot-Token` 헤더가 반드시 일치해야 합니다.

### 설정 API

*   **`/config` (GET)**: 현재 구성 조회
*   **`/config/endpoint` (POST)**: 기본 웹훅 엔드포인트(`hololive`) 업데이트
*   **`/config/dbrate` (POST)**: DB 폴링 속도 업데이트
*   **`/config/sendrate` (POST)**: 메시지 전송 속도 업데이트
*   **`/config/botport` (POST)**: HTTP 서버 포트 업데이트

`botToken`이 비어 있지 않으면 `/config` 읽기/쓰기에도 `X-Bot-Token` 헤더가 반드시 일치해야 합니다.

## 주요 변경 사항 (v2.0)

- Legacy bridge 제거
- H2cDispatcher를 앱 내부 비동기 큐 + 워커 풀 기반 전송기로 단순화
- route별 webhook 맵과 `X-Iris-Message-Id` 기준으로 전송 경로를 통일

---

## Troubleshooting

### outbox 디렉터리 모니터링

- 경로: `/data/local/tmp/iris-webhook-outbox`
- 정상 기대 상태: 평상시에는 비어 있거나, 재시도 중인 메시지 파일이 잠시만 남아 있어야 합니다.
- 현재 구현 기준으로 동일 `messageId`는 같은 outbox 파일을 재사용합니다.
- 한 번의 worker pass는 최대 6회 시도하며, backoff는 대략 `1s + 2s + 4s + 8s + 16s (+ jitter)`입니다.
- 따라서 **짧은 실패가 이어질 때도 파일 1개가 수십 초 이상 남을 수 있고**, 각 요청이 timeout에 걸리면 한 cycle이 **약 3분~4분대**까지 길어질 수 있습니다.
- 수동 정리 기준:
  - webhook 주소/서비스 장애 원인을 먼저 해결한 뒤 판단하세요.
  - 같은 `messageId` 중복 처리 가능성을 검토하기 전에는 성급히 삭제하지 마세요.
  - 필요 시 Iris를 멈춘 뒤 파일을 별도 위치로 이동해 보존하고, 원인 분석 후 재주입 여부를 결정하는 편이 안전합니다.

## Credits

*   **SendMsg & Initial Concept:** Based on the work of `ye-seola/go-kdb`.
*   **KakaoTalk Decryption Logic:** Decryption methods from `jiru/kakaodecrypt`.

## Disclaimer

This project is provided for educational and research purposes only. The developers are not responsible for any misuse or damage caused by this software. Use it at your own risk and ensure you comply with all applicable laws and terms of service.
