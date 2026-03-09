# [ARCHIVE] Iris h2c 마이그레이션 상태

> **문서 상태: Archive / 장애 조사 기록**
>
> 최종 조사 갱신: 2026-03-06 00:10 KST  
> 아카이브 표기 갱신: 2026-03-09 KST
>
> 이 문서는 과거 장애 조사와 임시 판단을 보존하기 위한 기록입니다.
> 현재 `main`의 실제 동작 설명 문서가 아닙니다.
>
> 현재 기준:
> - `ObserverHelper.kt`는 이미 `H2cDispatcher`를 사용합니다.
> - Redis/Jedis 기반 outbox·consumer 경로는 “제거 대상”이 아니라 제거 완료 상태입니다.
> - 운영 검증 상태는 `hololive`만 확인되었고, `twentyq` / `turtle-soup`는 별도 검증이 필요합니다.

## 현재 문서를 읽는 방법

- 이 문서는 **원인 분석 히스토리**로만 사용하세요.
- 현재 운영/설정 기준은 `README.md`의 webhook/h2c 통합 가이드를 우선 참고하세요.
- 특히 IP 예시는 당시 환경 기준이므로, 현재에는 반드시 “Iris에서 도달 가능한 주소”인지 다시 확인해야 합니다.

## 1. 문제 발생 경위

홀로봇이 Docker Compose에서 k8s(k3s)로 마이그레이션된 이후 Iris → 봇 간 통신이 완전히 끊김.
봇은 정상 기동(Pod Running, health OK)되지만 KakaoTalk 메시지에 대한 응답이 없는 상태.

### 인프라 변경 전후 비교

| 항목 | 이전 (Docker Compose) | 현재 (k8s) |
|---|---|---|
| 봇 실행 환경 | Docker 컨테이너 (`hololive-kakao-bot-go`) | k8s Pod (`hololive-bot-*`) |
| 봇 네트워크 | Docker bridge `20q-kakao-bot_llm-bot-net` | k8s CNI (`10.42.0.0/24`) |
| 봇 IP | `172.18.0.3` (Docker 할당) | `10.42.0.40` (Pod IP, 재시작 시 변동) |
| 봇 접근 경로 | Docker 네트워크 직접 IP | NodePort `30001` (호스트 모든 인터페이스) |
| Iris 실행 환경 | Redroid Docker 컨테이너 (변경 없음) | 동일 |
| Iris 네트워크 | `20q-kakao-bot_llm-bot-net` (봇과 동일) | Docker bridge만 (k8s CNI 접근 불가) |

### 통신 경로 변경

```
# 이전: 같은 Docker 네트워크
Iris(Redroid) → 172.18.0.3:30001 → 봇 컨테이너

# 현재: Docker gateway → 호스트 iptables → k8s NodePort → Pod
Iris(Redroid) → 172.18.0.1:30001 → 호스트 iptables DNAT → 10.42.0.40:30001
```

## 2. 진단 과정에서 발견된 문제 (3건)

### 2-1. webhook URL IP 불일치 (해결)

Iris config.json의 hololive webhook URL이 `172.18.0.3:30001`(존재하지 않는 이전 Docker IP)을 가리키고 있었음.

**수정**: `172.18.0.1:30001` (Redroid Docker gateway IP → 호스트 NodePort)

```json
// 수정 전
"webhooks": {
  "hololive": "http://172.18.0.3:30001/webhook/iris"
}

// 수정 후
"webhooks": {
  "hololive": "http://172.18.0.1:30001/webhook/iris"
}
```

**검증 (당시)**: Redroid 내부에서 `172.18.0.1:30001` TCP 연결 성공, 봇이 HTTP 응답 반환 확인.

**추가 점검 (2026-03-09)**: 현재 환경에서는 `172.18.0.2:30001`이 도달 가능했고, `172.18.0.1:30001`은 도달 불가였습니다. 고정 IP 자체보다 “Iris에서 실제로 도달 가능한 주소”인지가 핵심입니다.

> 참고: webhook URL은 “서버가 bind한 주소”가 아니라 “Iris에서 실제로 도달 가능한 주소”를 써야 합니다.  
> `127.0.0.1`은 Iris 자신의 loopback이므로, 외부 host/container/k8s service를 가리키는 주소로 사용할 수 없습니다.

### 2-2. assistantFullRooms 빈 배열 (해결)

config.json의 `assistantFullRooms`가 `[]`로 설정되어 있어, 홀로봇 채팅방이 full-access 모드로 등록되지 않음.

**수정**: `["451788135895779"]` 추가

**검증**: Iris 로그에서 `isAssistantFullRoom check: result=true` 확인.

### 2-3. Iris 코드 상태 불일치 — dispatch 미작동 (미해결, 핵심)

Iris가 KakaoTalk DB에서 메시지를 정상 감지하지만, **webhook dispatch가 실행되지 않음**.

```
# Iris 로그 — 메시지 감지는 되지만 dispatch 관련 로그 전무
[ObserverHelper] _id=3052, origin=MSG, message='!도움', isCommand=true, isFullRoom=true
[ObserverHelper] _id=3053, origin=MSG, message='!도움', isCommand=true, isFullRoom=true
# → [H2cDispatcher] 로그 없음, 봇 로그에도 webhook 수신 기록 없음
```

**원인**: 커밋된 `ObserverHelper.kt`가 `RedisProducer`(MQ 방식)를 참조하고 있어, H2cDispatcher를 사용하지 않음.

## 3. Iris 코드 현재 상태 분석

> 아래 내용은 당시 조사 시점의 판단이며, 현재 `main`과 다를 수 있습니다.

### 3-1. Git 상태 (`~/gemini/Iris`, branch: main)

```
610e39d fix(bridge): MOLTBOT 스트림을 OPENCLAW로 통합
f89cee6 feat: add assistantFullRooms whitelist for proactive assistant routing
6642804 feat: add assistant-bot support with /어시 prefix and kakao:assistant stream
b1ca47b refactor: Remove legacy WebSocket/OkHttp and optimize for MQ-only  ← MQ 전환
c9bc18c revert: Rollback keepAliveInterval to 60 seconds
```

커밋 `b1ca47b`에서 OkHttp/WebSocket을 제거하고 MQ(Redis/MQTT) 전용으로 리팩토링했음.
이후 커밋들은 MQ 방식 위에서 기능 추가.

### 3-2. 파일별 상태

| 파일 | Git 상태 | 내용 |
|---|---|---|
| `ObserverHelper.kt` | **커밋됨** | `RedisProducer`/`RedisReplyConsumer` 참조 (MQ 방식) |
| `bridge/H2cDispatcher.kt` | **untracked** | OkHttp + Kotlin coroutine 기반 h2c dispatcher |
| `bridge/RedisProducer.kt` | 커밋됨 | Redis LPUSH 기반 메시지 생산자 |
| `bridge/RedisReplyConsumer.kt` | 커밋됨 | Redis BRPOP 기반 응답 소비자 |

### 3-3. 빌드 의존성 (`libs.versions.toml` + `build.gradle.kts`)

| 의존성 | 상태 | 비고 |
|---|---|---|
| `ktor-server-cio` | 있음 | Iris HTTP 서버 (포트 3000) |
| `ktor-server-status-pages` | 있음 | |
| `ktor-server-content-negotiation` | 있음 | |
| `kotlinx-serialization-json` | 있음 | |
| `redis.clients:jedis:5.2.0` | 있음 | MQ 방식용 Redis 클라이언트 |
| `okhttp` | **제거됨** | H2cDispatcher.kt가 참조하지만 빌드 불가 |
| `kotlinx-coroutines-core` | **없음** | H2cDispatcher.kt가 참조하지만 빌드 불가 |

### 3-4. 핵심 문제 요약

```
ObserverHelper.kt (커밋됨)
  → import RedisProducer     ← 현재 활성
  → import RedisReplyConsumer ← 현재 활성
  (H2cDispatcher 참조 없음)

H2cDispatcher.kt (untracked)
  → import okhttp3.*          ← 의존성 없음, 빌드 불가
  → import kotlinx.coroutines ← 의존성 없음, 빌드 불가
```

**ObserverHelper가 H2cDispatcher를 사용하도록 전환해야 하고, H2cDispatcher는 OkHttp 대신 Ktor HttpClient로 재작성해야 함.**

## 4. 다음 작업 (우선순위순)

> 아래 내용은 당시 조사 시점의 작업 계획이며, 현재 우선순위와 다를 수 있습니다.

### P0: Iris h2c dispatch 복원

1. **H2cDispatcher를 Ktor HttpClient 기반으로 재작성**
   - `ktor-client-cio` 의존성 추가 (Ktor 서버는 이미 CIO 사용 중)
   - OkHttp → Ktor HttpClient 전환
   - Kotlin coroutine은 Ktor가 transitive로 포함하므로 별도 추가 불필요
   - `dispatchWithRetry`의 retry/backoff 로직 유지

2. **ObserverHelper.kt에서 RedisProducer → H2cDispatcher 전환**
   - `initDispatcher()` 메서드에서 H2cDispatcher 초기화
   - `route()` 호출 경로 연결
   - RedisProducer/RedisReplyConsumer import 제거

3. **jedis 의존성 정리 판단**
   - h2c 전환 후 Redis가 불필요하면 jedis 제거
   - 다른 곳에서 사용 중이면 유지

4. **APK 빌드 → 배포 → 검증**
   - `./gradlew assembleDebug`
   - `adb push` → Iris 재시작
   - Iris 로그에서 `[H2cDispatcher]` dispatch 성공/실패 로그 확인
   - 봇 로그에서 webhook 수신 확인
   - KakaoTalk에서 `!도움` → 봇 응답 확인

### P1: 안정성 보강

5. **iris-watchdog config template 갱신**
   - `~/gemini/llm/configs/iris/config.json` 템플릿에 `assistantFullRooms` 추가
   - watchdog의 `resolve_webhook_defaults()`가 k8s 환경에서 gateway fallback 정상 동작 확인됨

6. **`twentyq` / `turtle-soup` webhook**
   - 당시 조사 기준으로는 별도 reachability 검증이 완료되지 않았습니다.
   - 현재도 운영 검증 상태는 `hololive`만 확인되었고, `twentyq` / `turtle-soup`는 미검증 상태로 취급하는 것이 안전합니다.

## 5. 이번 세션에서 변경한 것

### 디바이스 (`/data/local/tmp/`)

| 파일 | 변경 내용 |
|---|---|
| `config.json` | webhook URL 수정 (`172.18.0.1`), `assistantFullRooms` 추가 |
| `Iris.apk` | 이번 세션에서 빌드한 임시 APK로 덮어씀 (원본 복원 필요 가능) |

### 호스트

| 파일/서비스 | 변경 내용 |
|---|---|
| `/etc/systemd/system/iris-watchdog.service` | 신규 설치, enabled, active |
| `~/gemini/Iris/gradle/libs.versions.toml` | okhttp/coroutines 제거, 사용자가 추가 수정 |
| `~/gemini/Iris/app/build.gradle.kts` | jedis 추가, 사용자가 추가 수정 |

### 변경하지 않은 것

- 봇 Go 코드 (변경 불필요 — 봇은 HTTP/1.1, h2c 모두 수락)
- k8s 매니페스트
- Iris Git 커밋 (모든 변경은 unstaged/untracked)

## 6. 참고 경로

```
~/gemini/Iris/                              # Iris 소스 (Kotlin/Android)
~/gemini/Iris/app/src/main/java/party/qwer/iris/
  ObserverHelper.kt                         # DB polling → dispatch 연결 (수정 대상)
  bridge/H2cDispatcher.kt                   # h2c dispatcher (재작성 대상, untracked)
  bridge/RedisProducer.kt                   # MQ producer (교체 대상)
  bridge/RedisReplyConsumer.kt              # MQ consumer (교체 대상)
~/gemini/Iris/gradle/libs.versions.toml     # 의존성 버전 카탈로그
~/gemini/Iris/app/build.gradle.kts          # 빌드 설정

~/gemini/llm/scripts/iris-init.sh           # Iris 초기화 스크립트
~/gemini/llm/scripts/iris-watchdog.sh       # Iris 프로세스 감시 (systemd)
~/gemini/llm/configs/iris/config.json       # config 템플릿 (envsubst)
~/gemini/Iris/iris_control                  # h2c 버전 수동 제어
~/gemini/iris_control                       # MQ 버전 수동 제어 (구)

# 봇 webhook handler (참고용, 수정 불필요)
hololive-bot/hololive/hololive-shared/pkg/iris/webhook_handler.go
hololive-bot/shared-go/pkg/irisx/types.go   # WebhookRequest 스키마
```
