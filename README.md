# Iris - 안드로이드 네이티브 DB기반 봇 프레임워크

이 프로젝트는 카카오톡 안드로이드 앱의 데이터베이스와 연동하여 MQTT 기반 채팅 봇을 작성할 수 있는 환경을 제공합니다.

**프로젝트 상태:** 베타

## 확장 기능

이 포크 버전은 원본 Iris에 다음 기능이 추가되었습니다:

- ✅ **MQTT Pub/Sub**: 실시간 메시지 라우팅 (비동기 통신)
- ✅ **MqttPublisher**: Prefix 기반 멀티봇 라우팅 (Longest-match 전략)
- ✅ **MqttSubscriber**: 봇 응답 수신 및 카카오톡 전송
- ✅ **BotRoute 모델**: MQTT Topic 기반 동적 라우팅 설정

이 기능들은 원본 Iris 프로젝트에는 없는 확장 구현입니다.

## 시작하기

### 필요 조건

*   **안드로이드 기기:** 이 애플리케이션은 카카오톡이 설치되어 있는 안드로이드 기기에서 실행되도록 설계되었습니다.
*   **루트 권한:** 카카오톡 데이터베이스와 일부 시스템 서비스에 접근하기 위하여 루트 권한이 필요합니다.
*   **MQTT Broker:** 메시지 라우팅을 위한 MQTT Broker (예: Mosquitto, EMQX)가 필요합니다.

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

## MQTT 기반 봇 통합 가이드

### 아키텍처 개요

Iris는 MQTT Pub/Sub 패턴을 사용하여 여러 봇 서비스와 비동기 통신합니다:

```
카카오톡 메시지
    ↓
Iris (MqttPublisher)
    ↓
MQTT Broker
    ↓
Bot Service (MQTT Subscribe)
    ↓
Bot Logic Processing
    ↓
MQTT Broker (Reply Topic)
    ↓
Iris (MqttSubscriber)
    ↓
카카오톡 응답 전송
```

### MQTT Broker 설정

#### Docker로 Mosquitto 실행 (권장)

```bash
docker run -d \
  --name mosquitto \
  -p 1883:1883 \
  eclipse-mosquitto:latest
```

#### Mosquitto 설정 파일 (mosquitto.conf)

```conf
listener 1883
allow_anonymous true
max_keepalive 60
```

### Iris 설정

#### config.json 예시

```json
{
  "botName": "Iris",
  "botHttpPort": 3000,
  "mqttBrokerUrl": "tcp://172.17.0.1:1883",
  "routes": [
    {
      "prefix": "!20q",
      "mqttTopic": "iris/bot/20q",
      "enabled": true
    },
    {
      "prefix": "/홀로",
      "mqttTopic": "iris/bot/holo",
      "enabled": true
    },
    {
      "prefix": "!",
      "mqttTopic": "iris/bot/default",
      "enabled": true
    }
  ],
  "dbPollingRate": 100,
  "messageSendRate": 50
}
```

**설정 설명:**
- `mqttBrokerUrl`: MQTT Broker 주소 (tcp://host:port)
- `routes`: 봇 라우팅 규칙 배열
  - `prefix`: 메시지 시작 패턴 (Longest-match 전략)
  - `mqttTopic`: 해당 봇의 MQTT 구독 topic
  - `enabled`: 라우트 활성화 여부

#### Longest-Match 라우팅 전략

```
메시지: "!20q 질문"       → "!20q" 매칭 (우선순위 높음)  → iris/bot/20q
메시지: "/홀로라이브 공지" → "/홀로" 매칭               → iris/bot/holo
메시지: "!help"          → "!" 매칭 (catch-all)        → iris/bot/default
메시지: "안녕하세요"      → 매칭 없음                   → 라우팅 안 함
```

---

## 봇 서비스 개발 가이드

### 1. 메시지 수신 (Subscribe)

봇 서비스는 Iris가 발행하는 메시지를 구독합니다.

#### Topic 패턴
```
iris/bot/{botId}
```

#### Payload 형식 (JSON)

```json
{
  "msg": "[DECRYPTED_MESSAGE]",
  "room": "[CHAT_ROOM_NAME]",
  "sender": "[SENDER_NAME]",
  "json": {
    "_id": "12345",
    "chat_id": "1234567890",
    "user_id": "9876543210",
    "message": "[DECRYPTED_MESSAGE]",
    "attachment": "[DECRYPTED_ATTACHMENT]",
    "created_at": "1234567890",
    "v": "{\"enc\": 0, ...}"
  }
}
```

**⚠️ 중요:**
- `room`: 채팅방 **이름** (예: "친구와의 채팅")
- `json.chat_id`: 채팅방 **ID** (숫자) ← **응답 시 이 값 사용 필수**

### 2. 응답 전송 (Publish)

봇이 처리 결과를 Iris로 전송합니다.

#### Reply Topic 패턴
```
iris/bot/{botId}/reply
```

#### Payload 형식 (JSON)

```json
{
  "type": "text",
  "room": "1234567890",
  "threadId": null,
  "data": "봇 응답 메시지"
}
```

**필드 설명:**
- `type`: 메시지 타입 (`"text"`, `"image"` 등)
- `room`: **chat_id** (수신한 `json.chat_id` 값)
- `threadId`: 스레드 답장 ID (없으면 null)
- `data`: 전송할 메시지 내용

### 3. Python 예제 (paho-mqtt)

```python
import paho.mqtt.client as mqtt
import json

BROKER = "172.17.0.1"
PORT = 1883
BOT_ID = "20q"

def on_connect(client, userdata, flags, rc):
    print(f"Connected to MQTT Broker: {rc}")
    # 메시지 수신 구독
    client.subscribe(f"iris/bot/{BOT_ID}")

def on_message(client, userdata, msg):
    payload = json.loads(msg.payload.decode('utf-8'))

    message = payload['msg']
    chat_id = payload['json']['chat_id']  # ⚠️ chat_id 추출

    print(f"Received: {message} from room {chat_id}")

    # 봇 로직 처리
    if message.startswith("!20q"):
        response = "20 Questions 게임을 시작합니다!"

        # 응답 전송
        reply = {
            "type": "text",
            "room": chat_id,  # chat_id 사용
            "threadId": None,
            "data": response
        }
        client.publish(f"iris/bot/{BOT_ID}/reply", json.dumps(reply))

client = mqtt.Client()
client.on_connect = on_connect
client.on_message = on_message

client.connect(BROKER, PORT, 60)
client.loop_forever()
```

### 4. Node.js 예제 (mqtt.js)

```javascript
const mqtt = require('mqtt');

const BROKER = 'mqtt://172.17.0.1:1883';
const BOT_ID = 'holo';

const client = mqtt.connect(BROKER);

client.on('connect', () => {
  console.log('Connected to MQTT Broker');
  client.subscribe(`iris/bot/${BOT_ID}`);
});

client.on('message', (topic, message) => {
  const payload = JSON.parse(message.toString());

  const msg = payload.msg;
  const chatId = payload.json.chat_id;  // ⚠️ chat_id 추출

  console.log(`Received: ${msg} from room ${chatId}`);

  // 봇 로직
  if (msg.startsWith('/홀로')) {
    const response = '홀로라이브 정보 봇입니다!';

    // 응답 전송
    const reply = {
      type: 'text',
      room: chatId,
      threadId: null,
      data: response
    };

    client.publish(`iris/bot/${BOT_ID}/reply`, JSON.stringify(reply));
  }
});
```

### 5. Kotlin/Spring Boot 예제

```kotlin
import org.eclipse.paho.client.mqttv3.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

@Serializable
data class IrisMessage(
    val msg: String,
    val room: String,
    val sender: String,
    val json: Map<String, Any>
)

@Serializable
data class BotReply(
    val type: String,
    val room: String,
    val threadId: String? = null,
    val data: String
)

class Bot20Q {
    private val client = MqttClient("tcp://172.17.0.1:1883", "bot-20q")
    private val json = Json { ignoreUnknownKeys = true }

    init {
        client.setCallback(object : MqttCallback {
            override fun messageArrived(topic: String, message: MqttMessage) {
                handleMessage(message)
            }

            override fun connectionLost(cause: Throwable?) {
                println("Connection lost: ${cause?.message}")
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        client.connect()
        client.subscribe("iris/bot/20q", 1)
    }

    private fun handleMessage(message: MqttMessage) {
        val payload = String(message.payload, Charsets.UTF_8)
        val msg = json.decodeFromString<IrisMessage>(payload)

        val chatId = msg.json["chat_id"].toString()  // ⚠️ chat_id 추출

        if (msg.msg.startsWith("!20q")) {
            val reply = BotReply(
                type = "text",
                room = chatId,
                data = "20 Questions 게임을 시작합니다!"
            )

            val replyJson = json.encodeToString(BotReply.serializer(), reply)
            val mqttMsg = MqttMessage(replyJson.toByteArray())
            mqttMsg.qos = 1

            client.publish("iris/bot/20q/reply", mqttMsg)
        }
    }
}
```

---

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

**참고:** MQTT 방식이 권장되며, WebSocket은 레거시 지원용입니다.

---

## 주요 변경 사항 (v2.0)

### 기존 (WebhookRouter)
```
Iris → HTTP POST → Bot Webhook
Bot → IrisClient HTTP → Iris
```

### 현재 (MQTT)
```
Iris → MQTT Pub → MQTT Broker → Bot
Bot → MQTT Pub → MQTT Broker → Iris
```

**마이그레이션:**
1. MQTT Broker 설치 (Mosquitto 권장)
2. `config.json`에서 `webhookUrl` → `mqttTopic` 변경
3. 봇 서비스에서 MQTT Client 구현
4. Reply Topic으로 응답 전송

---

## Troubleshooting

### MQTT 연결 실패
```
[MqttPublisher] Failed to connect: Connection refused
```

**해결:**
1. MQTT Broker가 실행 중인지 확인
2. `mqttBrokerUrl` 주소 확인 (Docker: tcp://172.17.0.1:1883)
3. 방화벽 설정 확인

### 봇이 메시지를 받지 못함
```
[MqttPublisher] No matching routes for message
```

**해결:**
1. `routes` 설정에서 `prefix` 확인
2. `enabled: true` 설정 확인
3. Longest-match 전략 이해 (긴 prefix가 우선)

### 응답 메시지가 전송되지 않음

**해결:**
1. Reply topic 형식 확인: `iris/bot/{botId}/reply`
2. `room` 필드에 `chat_id` (숫자) 사용 확인
3. MqttSubscriber 로그 확인

---

## Credits

*   **SendMsg & Initial Concept:** Based on the work of `ye-seola/go-kdb`.
*   **KakaoTalk Decryption Logic:** Decryption methods from `jiru/kakaodecrypt`.

## Disclaimer

This project is provided for educational and research purposes only. The developers are not responsible for any misuse or damage caused by this software. Use it at your own risk and ensure you comply with all applicable laws and terms of service.
