# Iris - 안드로이드 네이티브 DB기반 봇 프레임워크

이 프로젝트는 카카오톡 안드로이드 앱의 데이터베이스와 연동하여 gRPC 기반 채팅 봇을 작성할 수 있는 환경을 제공합니다.

**프로젝트 상태:** 베타

## 확장 기능

이 포크 버전은 원본 Iris에 다음 기능이 추가되었습니다:


이 기능들은 원본 Iris 프로젝트에는 없는 확장 구현입니다.

## 시작하기

### 필요 조건

*   **안드로이드 기기:** 이 애플리케이션은 카카오톡이 설치되어 있는 안드로이드 기기에서 실행되도록 설계되었습니다.
*   **루트 권한:** 카카오톡 데이터베이스와 일부 시스템 서비스에 접근하기 위하여 루트 권한이 필요합니다.
*   **백엔드 통신:** 다음 중 하나를 선택합니다.
    - gRPC 서버 엔드포인트(내부 네트워크): 20Q Bot gRPC 서버

### 설치

1.  **최신 Iris를 [Releases](https://github.com/park285/Iris/releases)에서 다운로드하세요.**

2.  **파일 복사:**
    adb를 사용하여 Iris apk 파일을 안드로이드 환경에 복사하세요.
    ```bash
    adb push Iris.apk /data/local/tmp
    ```

3.  **apk 파일 실행:**

    iris_control을 실행 가능하게 만드세요.(윈도우 이용자는 skip)
    ```bash
    chmod +x iris_control
    ```

    실행하려면 iris_control을 사용하세요.(윈도우 이용자는 ./iris_control.ps1)
    ```bash
    ./iris_control start
    ```

    iris_control은 install/start/status/stop 명령어를 제공합니다.

4.  **Config 설정:**

    `http://[ANDROID_IP]:3000/dashboard` 에 브라우저를 통해 접속하여, 설정을 진행합니다.

---

## gRPC 기반 봇 통합 가이드 (내부 통신 권장)

### 아키텍처 개요

Iris는 gRPC 양방향 스트리밍으로 20Q 봇과 직접 통신할 수 있습니다:

```
카카오톡 메시지
    ↓
Iris (GrpcBridge)
    ↔ gRPC (HTTP/2 over TCP)
20Q Bot (gRPC Server)
    ↓
카카오톡 응답 전송 (Iris)
```

### Iris 설정 (MQ 브리지)

`/data/local/tmp/config.json` (또는 `IRIS_CONFIG_PATH`)에서 MQ/Redis 브리지를 설정합니다.

```json
{
  "botName": "Iris",
  "botHttpPort": 3000,
  "mqHost": "127.0.0.1",
  "mqPort": 1833,
  "dbPollingRate": 100,
  "messageSendRate": 50
}
```

설명:
- `mqHost`/`mqPort`: Valkey/Redis MQ 서버 주소/포트(로컬 또는 컨테이너 네트워크)

### 서버 준비(요약)
- 20Q 쪽에서 `.proto` 기반 gRPC 서버에 `Chat(stream)` 구현 필요
- 내부 네트워크 신뢰 기준: 평문(`usePlaintext`) 권장, 필요 시 mTLS 구성

---

## gRPC 기반 봇 통합 가이드

### 아키텍처 개요


```
카카오톡 메시지
    ↓
    ↓
    ↓
    ↓
Bot Logic Processing
    ↓
    ↓
    ↓
카카오톡 응답 전송
```

#
## 봇 서비스 개발 가이드 (gRPC)

### 메시지 스키마 (proto 요약)
- ChatMessage: text, room, sender, raw_json, thread_id
- ChatReply: room, reply, thread_id, done
- Service: service TwentyQ { rpc Chat(stream ChatMessage) returns (stream ChatReply); }

### 서버 구현 개요
- Iris는 명령 프리픽스("!", "/")로 시작하는 메시지만 gRPC로 전송합니다.
- 서버는 Chat(stream)에서 클라이언트 메시지 수신 시 처리 후 ChatReply를 스트림으로 반환합니다.

### Kotlin gRPC 서버 스니펫
```kotlin
class TwentyQService : TwentyQGrpc.TwentyQImplBase() {
  override fun chat(resp: StreamObserver<ChatReply>): StreamObserver<ChatMessage> {
    return object : StreamObserver<ChatMessage> {
      override fun onNext(req: ChatMessage) {
        val reply = ChatReply.newBuilder()
          .setRoom(req.room) // echo room
          .setReply("pong: ${'$'}{req.text}")
          .setThreadId(req.threadId)
          .build()
        resp.onNext(reply)
      }
      override fun onError(t: Throwable) {}
      override fun onCompleted() { resp.onCompleted() }
    }
  }
}
```

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
    curl -X POST -H "Content-Type: application/json" \
      -d '{"type": "text", "room": "1234567890", "data": "메시지 전송!"}' \
      http://[YOUR_DEVICE_IP]:3000/reply
    ```

*   **`/query`**: 카카오톡 데이터베이스에 SQL 쿼리를 실행합니다.

    **예시:**

    ```bash
    curl -X POST -H "Content-Type: application/json" \
      -d '{"query": "SELECT _id, chat_id, message FROM chat_logs ORDER BY _id DESC LIMIT 5"}' \
      http://[YOUR_DEVICE_IP]:3000/query
    ```

*   **`/decrypt`**: 카카오톡 메시지를 복호화합니다.

*   **`/aot` (GET)**: AOT 토큰을 리턴합니다.

### 설정 API

*   **`/dashboard` (GET)**: 웹 UI 설정 페이지
*   **`/config` (GET)**: 현재 구성 조회
*   **`/config/endpoint` (POST)**: 웹 서버 엔드포인트 업데이트
*   **`/config/dbrate` (POST)**: DB 폴링 속도 업데이트
*   **`/config/sendrate` (POST)**: 메시지 전송 속도 업데이트
*   **`/config/botport` (POST)**: HTTP 서버 포트 업데이트

---

## WebSocket 엔드포인트 (레거시)

*   **`/ws`**: WebSocket 연결 생성 (실시간 메시지 수신)


---

## 주요 변경 사항 (v2.0)

### 기존 (WebhookRouter)
```
Iris → HTTP POST → Bot Webhook
Bot → IrisClient HTTP → Iris
```

```
```

**마이그레이션:**

---

## Troubleshooting

#
## Credits

*   **SendMsg & Initial Concept:** Based on the work of `ye-seola/go-kdb`.
*   **KakaoTalk Decryption Logic:** Decryption methods from `jiru/kakaodecrypt`.

## Disclaimer

This project is provided for educational and research purposes only. The developers are not responsible for any misuse or damage caused by this software. Use it at your own risk and ensure you comply with all applicable laws and terms of service.
