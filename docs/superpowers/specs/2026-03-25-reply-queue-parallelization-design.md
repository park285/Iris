# ReplyService Per-Thread 병렬화 설계

## 배경

현재 `ReplyService`는 모든 reply 요청을 단일 글로벌 `Channel<SendMessageRequest>(64)`에 적재하고
단일 coroutine 워커가 순차 처리한다. 이미지 thread graft 경로가 room 단위 블로킹을 더 이상 요구하지 않으므로,
서로 다른 thread에 대한 reply를 병렬 처리하여 응답 지연을 줄인다.

## 목표

- 같은 `(chatId, threadId)` 내에서는 순서 보장 + 워커별 독립 rate limit
- 다른 `(chatId, threadId)` 조합은 병렬 처리 (전처리 병렬, 전송은 mutex로 동시 호출 방지)
- 비정상 부하 시 fail-fast (429)

### Rate Limit 정책

`messageSendRate` delay는 **워커별 독립** 적용. 글로벌 전송 간 최소 간격은 보장하지 않음.
글로벌 mutex는 동시 API 호출 방지 목적이며, rate limiting 수단이 아님.

- 워커 A 전송 완료 → mutex unlock → 워커 A가 독립 delay(50ms) 시작
- 워커 B는 mutex unlock 직후 즉시 전송 가능 (워커 B 자신의 이전 전송으로부터 50ms 경과했다면)
- 결과: 서로 다른 워커 간 전송은 수 ms 내 연속 가능. 같은 워커 내 전송만 50ms 간격 보장

## 비목표

- 전송 throughput 자체 증가 (글로벌 mutex로 실제 전송은 직렬)
- config 동적 변경 (상수로 관리)
- 미사용 경로(`sendThreadMarkdown`, `sendThreadTextShare`) 변경

## 용어

| 용어 | 의미 |
|---|---|
| reply worker | 특정 `(chatId, threadId)` 조합 전담 coroutine |
| worker registry | 활성 워커를 관리하는 `ConcurrentHashMap<ReplyQueueKey, ReplyWorkerState>` |
| send mutex | 실제 KakaoTalk API 호출 시점의 동시 실행 방지용 `kotlinx.coroutines.sync.Mutex` |

## 설계

### 병렬화 키

```kotlin
data class ReplyQueueKey(val chatId: Long, val threadId: Long?)
```

- `threadId == null`: 해당 방의 비쓰레드 메시지 전용 워커
- 같은 `chatId`라도 `threadId`가 다르면 별도 워커

### 상수

```kotlin
private companion object {
    const val PER_WORKER_QUEUE_CAPACITY = 16
    const val MAX_WORKERS = 16
    const val WORKER_IDLE_TIMEOUT_MS = 60_000L
    const val LOG_MESSAGE_PREVIEW_LENGTH = 30
}
```

- 총 시스템 큐 용량: `16 * 16 = 256`
- `messageSendRate`는 기존 `ConfigProvider`에서 읽음 (기본 50ms)

### 워커 레지스트리

```kotlin
private val workerRegistry = ConcurrentHashMap<ReplyQueueKey, ReplyWorkerState>()
private val sendMutex = Mutex()

private data class ReplyWorkerState(
    val key: ReplyQueueKey,
    val channel: Channel<SendMessageRequest>,
    val job: Job,
)
```

- `getOrCreateWorker(key)`: registry 조회 -> 없으면 생성, 풀 full이면 null 반환
- 워커 생성은 `synchronized` 블록 내에서 double-check

### 워커 생명주기

```
생성: 해당 키로 첫 메시지 도착 시
실행: channel에서 메시지 소비 -> 전처리(병렬) -> sendMutex.withLock { hint 쓰기 + 전송 } -> delay(messageSendRate)
유휴: withTimeoutOrNull(60s)로 다음 메시지 대기, timeout 시 자체 종료
종료: channel.close() + registry에서 제거
```

워커 coroutine 의사코드:

```kotlin
private fun launchWorker(key: ReplyQueueKey): ReplyWorkerState {
    val channel = Channel<SendMessageRequest>(PER_WORKER_QUEUE_CAPACITY)
    val job = coroutineScope.launch {
        try {
            while (true) {
                val request = withTimeoutOrNull(WORKER_IDLE_TIMEOUT_MS) {
                    channel.receive()
                } ?: break  // idle timeout -> 종료

                try {
                    sendMutex.withLock {
                        request.send()
                    }
                    delay(config.messageSendRate)
                } catch (e: Exception) {
                    IrisLogger.error("[ReplyService] worker($key) send error: ${e.message}", e)
                }
            }
        } finally {
            channel.close()
            // compare-remove: 자신이 등록된 상태일 때만 제거 (race 방지)
            workerRegistry.remove(key, this@ReplyWorkerState)
            IrisLogger.debug("[ReplyService] worker($key) terminated (idle timeout)")
        }
    }
    return ReplyWorkerState(key, channel, job)
}
```

### 메시지 적재 (enqueueRequest)

```kotlin
@Synchronized
private fun enqueueRequest(
    chatId: Long,
    threadId: Long?,
    request: SendMessageRequest,
): ReplyAdmissionResult {
    if (!started) return shutdownResult()

    val key = ReplyQueueKey(chatId, threadId)
    val worker = getOrCreateWorker(key)
        ?: return ReplyAdmissionResult(ReplyAdmissionStatus.QUEUE_FULL, "too many active reply workers")

    val sendResult = worker.channel.trySend(request)
    return when {
        sendResult.isSuccess -> ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
        sendResult.isClosed -> {
            // 워커가 idle timeout으로 방금 종료된 경우 -> compare-remove 후 재시도 1회
            workerRegistry.remove(key, worker)
            val retryWorker = getOrCreateWorker(key)
                ?: return ReplyAdmissionResult(ReplyAdmissionStatus.QUEUE_FULL, "too many active reply workers")
            val retryResult = retryWorker.channel.trySend(request)
            if (retryResult.isSuccess) ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
            else ReplyAdmissionResult(ReplyAdmissionStatus.QUEUE_FULL, "reply queue is full")
        }
        else -> ReplyAdmissionResult(ReplyAdmissionStatus.QUEUE_FULL, "reply queue is full")
    }
}
```

### 동시 전송 방지

`kotlinx.coroutines.sync.Mutex`를 ReplyService 인스턴스 레벨에서 보유.

```
워커A: [base64 디코딩] [파일 쓰기] [mutex lock -> hint 쓰기 -> startActivity -> unlock] [delay 50ms]
워커B: [base64 디코딩] [파일 쓰기]     [mutex 대기]     [mutex lock -> hint 쓰기 -> startActivity -> unlock] [delay 50ms]
```

- 전처리(디코딩, 파일 I/O)는 mutex 바깥 -> 병렬
- **thread hint 작성 + KakaoTalk API 호출**은 mutex 안 -> 직렬
  - `writeThreadHint()`는 단일 파일(`/data/local/tmp/iris-thread-hint.json`)에 쓰므로,
    mutex 바깥에서 실행하면 다른 워커가 덮어쓸 수 있음
  - hint 작성과 이미지 전송이 원자적으로 실행되어야 올바른 thread context 보장
- mutex 해제 후 각 워커가 독립적으로 `delay(messageSendRate)` 적용

### Lifecycle API

기존 `startMessageSender()` / `restartMessageSender()` / `shutdown()` 3개 메서드를 다음과 같이 전이:

| 기존 메서드 | 변경 |
|---|---|
| `startMessageSender()` | `start()` — `started = true` 설정. 워커는 lazy 생성이므로 별도 Job 시작 불필요 |
| `restartMessageSender()` | `restart()` — 아래 순서 참조 |
| `shutdown()` | 유지 — 아래 참조 |

`started` 플래그:
- `start()` 호출 시 `true`, `shutdown()` 호출 시 `false`
- `enqueueRequest`는 `started == false`이면 `SHUTDOWN` 반환
- shutdown 이후 재시작은 지원하지 않음 (기존과 동일 — channel close 후 재사용 불가)

`restart()` 순서 (동기화 경계):
1. `synchronized` 블록 내: `started = false` → enqueue 차단
2. `synchronized` 블록 내: 활성 워커 snapshot 고정 (`workerRegistry.values.toList()`)
3. snapshot의 모든 워커 channel close + job join (timeout 적용)
4. `synchronized` 블록 내: `workerRegistry.clear()` + `started = true` → enqueue 재개

### Shutdown

```kotlin
fun shutdown() {
    synchronized(this) { started = false }
    val workers = workerRegistry.values.toList()
    workers.forEach { it.channel.close() }
    runBlocking {
        workers.forEach { worker ->
            withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { worker.job.join() }
                ?: worker.job.cancel()
        }
    }
    workerRegistry.clear()
}
```

### MessageSender 인터페이스 변경

`enqueueRequest`에 `chatId`, `threadId` 파라미터가 필요하므로,
각 send 메서드 내부에서 이미 보유한 `chatId`/`room`과 `threadId`를 `enqueueRequest`에 전달.
`MessageSender` 인터페이스 자체는 변경 없음.

### 영향 범위

| 파일 | 변경 내용 |
|---|---|
| `ReplyService.kt` | 단일 channel -> worker registry, sendMutex 추가, enqueueRequest 시그니처 변경, shutdown 수정 |
| `ReplyAdmission.kt` | 변경 없음 (MessageSender 인터페이스 동일) |
| `IrisServer.kt` | 변경 없음 |
| `MessageSender.kt` | 변경 없음 |
| 테스트 | `ReplyService` 테스트 추가/수정 — 아래 테스트 시나리오 참조 |

### 테스트 시나리오

1. **동일 키 순서 보장** — 같은 `(chatId, threadId)`에 N개 메시지 적재 시 전송 순서 일치
2. **다른 키 병렬성** — 서로 다른 키의 전처리가 동시 실행됨을 검증 (전처리에 delay 삽입, 총 소요 시간 < 직렬 합)
3. **동시 전송 방지** — mutex로 인해 실제 send 호출이 겹치지 않음 (동시 호출 감지 시 실패)
4. **풀 초과 429** — MAX_WORKERS개 워커 활성 상태에서 새 키 요청 시 QUEUE_FULL 반환
5. **per-worker 큐 만석** — 특정 워커의 채널이 가득 찬 상태에서 같은 키 요청 시 QUEUE_FULL
6. **idle timeout 종료** — 메시지 없는 워커가 60초 후 registry에서 제거됨
7. **idle timeout race** — 워커 종료 직후 같은 키로 메시지 도착 시 새 워커 생성 + 정상 적재
8. **shutdown 거부** — shutdown 후 enqueue 시 SHUTDOWN 반환
9. **shutdown drain** — shutdown 시 대기 중 메시지가 처리 완료된 후 종료
10. **rate limit 독립성** — 서로 다른 워커의 delay가 독립적 (한 워커의 delay가 다른 워커를 블로킹하지 않음)

### 기존 동작과의 차이

| 항목 | 기존 | 변경 후 |
|---|---|---|
| 큐 구조 | 글로벌 단일 Channel(64) | per-(chatId, threadId) Channel(16), 최대 16개 |
| 워커 수 | 1 | 최대 16 (동적 생성/제거) |
| 전처리 | 직렬 | 병렬 |
| 전송 | 직렬 | 직렬 (mutex) |
| 다른 쓰레드 간 지연 | O (앞선 메시지 처리 대기) | X (독립 워커) |
| 총 큐 용량 | 64 | 256 (16 * 16) |
| 풀 초과 시 | N/A | 429 QUEUE_FULL |
