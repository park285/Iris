# Review: Outbox State Transition API 통합

> **상태**: 종결 — 현재 구현 반영 완료, 코드 스니펫/검증 항목 최신화 (2026-04-01)
>
> **목적**: `WebhookDeliveryStore`와 `WebhookOutboxDispatcher`의 현재 상태 전이 계약을 코드와 동일한 형태로 기록한다.
> 이 문서는 과거 설계 옵션 비교가 아니라, 현재 브랜치에 실제 반영된 구현과 남은 후속 항목을 정리하는 최종 스냅샷이다.

---

## 1. 현재 상태 요약

현재 영속 outbox 경로는 다음 항목까지 반영된 상태다.

- `resolveFailure()` 기반 실패 전이 통합
- `requeueClaim()` 기반 pre-attempt 시스템 사유 재큐잉
- row별 `claimToken`
- `failedAttemptCount` 이름 정리
- `ClaimTransitionResult(APPLIED / STALE_CLAIM)` 도입
- `markSent()` 시 `last_error = NULL` 정리
- 셧다운 race 수정
- `renewClaim()` 기반 claim lease heartbeat
- `WebhookDeliveryPolicy`의 delivery timeout / heartbeat / lease 정합성 guard
- deterministic pre-attempt input 실패와 unexpected local failure를 분리하는 dispatcher 의미 수정
- `DeterministicPreAttemptRejectException` 기반 deterministic reject taxonomy
- `WebhookDeliveryClient.execute()`의 cancellation-aware timeout 경로

현재 문서 기준으로 남아 있는 후속 항목은 아래 두 가지다.

- `STALE_CLAIM` 관측을 로그 수준이 아닌 정량 메트릭으로 올릴지 여부
- `processEntry()` 책임을 더 잘게 분리할지 여부

---

## 2. 범위와 비범위

이 문서의 범위는 SQLite 기반 영속 outbox 경로다.

| 경로 | 클래스 | 영속성 | 상태 모델 |
|---|---|---|---|
| 영속 outbox | `WebhookOutboxDispatcher` + `SqliteWebhookDeliveryStore` | SQLite `webhook_outbox` | `PENDING -> CLAIMED -> SENT/RETRY/DEAD` |
| 파일 기반 레거시 outbox | `FileWebhookOutboxStore` | JSON 파일 | 별도 모델, 본 문서 범위 아님 |
| 인메모리 직접 전달 | `H2cDispatcher` | 없음 | 별도 retry/outcome 모델, 본 문서 범위 아님 |

---

## 3. 현재 상태 전이 다이어그램

```text
                   enqueue (INSERT OR IGNORE)
                          │
                          ▼
                       PENDING
                          │
                          │ claimReady(limit)
                          ▼
                       CLAIMED
                    claim_token per row
                          │
          ┌───────────────┼───────────────────────────────┐
          │               │                               │
          │               │                               │
      markSent      resolveFailure(...)             requeueClaim
          │               │                               │
          │               │                               │
          ▼               ▼                               ▼
        SENT          RETRY or DEAD                     RETRY
                         │
                         │ claimReady(limit)
                         ▼
                      CLAIMED

추가 전이:
- renewClaim(): CLAIMED lease 유지, 상태는 그대로 CLAIMED
- recoverExpiredClaims(): 만료된 CLAIMED -> RETRY
```

`CLAIMED`에서 가능한 현재 전이는 아래와 같다.

| 메서드 | 전이 | `attempt_count` | 용도 |
|---|---|---|---|
| `markSent` | `CLAIMED -> SENT` | 변경 없음 | HTTP 성공 |
| `resolveFailure(Retry)` | `CLAIMED -> RETRY` | `+1` | HTTP 시도 후 재시도 가능 실패 |
| `resolveFailure(PermanentFailure)` | `CLAIMED -> DEAD` | `+1` | HTTP 시도 후 영구 실패 또는 재시도 소진 |
| `resolveFailure(RejectedBeforeAttempt)` | `CLAIMED -> DEAD` | 변경 없음 | HTTP 시도 전 거부 |
| `requeueClaim` | `CLAIMED -> RETRY` | 변경 없음 | 셧다운, 큐 포화 등 시스템 사유. HTTP 시도 전에만 사용 |
| `renewClaim` | `CLAIMED -> CLAIMED` | 변경 없음 | 장시간 전송 중 lease heartbeat |
| `recoverExpiredClaims` | `CLAIMED -> RETRY` | 변경 없음 | 만료 lease 회수 |

---

## 4. 현재 API 스냅샷

### 4.1. 상태 전이 계약

아래 스니펫은 현재 `WebhookDeliveryStore` 계약과 동일하다.

```kotlin
data class ClaimedDelivery(
    val id: Long,
    val messageId: String,
    val roomId: Long,
    val route: String,
    val payloadJson: String,
    /** 누적 실패 횟수. 성공 시 증가하지 않으므로 총 시도 횟수와 다르다. */
    val failedAttemptCount: Int,
    val claimToken: String,
)

/** CLAIMED 상태에서의 실패 처리 outcome. */
sealed interface FailureOutcome {
    /** 재시도 가능 실패. failedAttemptCount 증가. */
    data class Retry(
        val nextAttemptAt: Long,
        val reason: String?,
    ) : FailureOutcome

    /** HTTP 시도 후 영구 실패 (비재시도 상태코드, 재시도 소진). failedAttemptCount 증가. */
    data class PermanentFailure(
        val reason: String?,
    ) : FailureOutcome

    /** HTTP 시도 전 거부 (URL 미설정 등). failedAttemptCount 변경 없음. */
    data class RejectedBeforeAttempt(
        val reason: String?,
    ) : FailureOutcome
}

enum class ClaimTransitionResult {
    APPLIED,
    STALE_CLAIM,
}

interface WebhookDeliveryStore : Closeable {
    fun enqueue(delivery: PendingWebhookDelivery): Long

    fun claimReady(limit: Int): List<ClaimedDelivery>

    fun markSent(
        id: Long,
        claimToken: String,
    ): ClaimTransitionResult

    fun resolveFailure(
        id: Long,
        claimToken: String,
        outcome: FailureOutcome,
    ): ClaimTransitionResult

    fun requeueClaim(
        id: Long,
        claimToken: String,
        nextAttemptAt: Long,
        reason: String?,
    ): ClaimTransitionResult

    fun renewClaim(
        id: Long,
        claimToken: String,
    ): ClaimTransitionResult

    fun recoverExpiredClaims(olderThanMs: Long): Int
}
```

핵심 계약은 다음과 같다.

- `requeueClaim()`은 시도로 카운트하지 않는 `CLAIMED -> RETRY` 복귀다.
- `requeueClaim()`은 HTTP 시도 전에만 사용해야 한다.
- `resolveFailure(RejectedBeforeAttempt)`는 deterministic한 HTTP 시도 전 거부를 뜻한다.
- 모든 CLAIMED 전이는 `ClaimTransitionResult`를 반환해 stale token을 직접 드러낸다.

### 4.2. `ClaimTransitionObserver`

`STALE_CLAIM`을 호출자 쪽에서 관측할 수 있도록 observer 훅이 분리돼 있다.

```kotlin
internal fun interface ClaimTransitionObserver {
    fun onResult(
        operation: String,
        entry: ClaimedDelivery,
        result: ClaimTransitionResult,
    )
}
```

기본 dispatcher 구현은 `STALE_CLAIM`이면 warning 로그를 남긴다.

---

## 5. DB 스키마와 SQLite 구현

### 5.1. 스키마

현재 `webhook_outbox` 스키마는 아래와 같다.

```kotlin
CREATE TABLE IF NOT EXISTS webhook_outbox (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id TEXT NOT NULL UNIQUE,
    room_id INTEGER NOT NULL,
    route TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at INTEGER NOT NULL DEFAULT 0,
    claim_token TEXT,
    claimed_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    last_error TEXT
)
```

현재 status 값은 `PENDING`, `CLAIMED`, `RETRY`, `SENT`, `DEAD`를 사용한다.
`BLOCKED_CONFIG`는 현재 제품 정책 범위에 포함되지 않는다.

### 5.2. row별 claimToken

현재 `claimReady()`는 row마다 별도 UUID를 발급한다.

```kotlin
override fun claimReady(limit: Int): List<ClaimedDelivery> =
    db.inImmediateTransaction {
        val now = clock()
        val rows =
            query(
                """SELECT id, message_id, room_id, route, payload_json, attempt_count
                   FROM ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                   WHERE status IN ('PENDING', 'RETRY') AND next_attempt_at <= ?
                   ORDER BY id LIMIT ?""",
                listOf(now, limit),
            ) { row ->
                val id = row.getLong(0)
                val claimToken = UUID.randomUUID().toString()
                update(
                    """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                       SET status = 'CLAIMED', claim_token = ?, claimed_at = ?, updated_at = ?
                       WHERE id = ? AND status IN ('PENDING', 'RETRY')""",
                    listOf(claimToken, now, now, id),
                )
                ClaimedDelivery(
                    id = id,
                    messageId = row.getString(1),
                    roomId = row.getLong(2),
                    route = row.getString(3),
                    payloadJson = row.getString(4),
                    failedAttemptCount = row.getInt(5),
                    claimToken = claimToken,
                )
            }
        rows
    }
```

### 5.3. SQLite 상태 전이 구현

현재 구현의 핵심은 `id + claim_token + status = 'CLAIMED'` 조합으로 모든 전이를 보호한다는 점이다.

```kotlin
override fun markSent(
    id: Long,
    claimToken: String,
): ClaimTransitionResult =
    transitionResult(
        db.update(
            """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
               SET status = 'SENT', last_error = NULL,
                   claim_token = NULL, claimed_at = NULL, updated_at = ?
               WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'""",
            listOf(clock(), id, claimToken),
        ),
    )

override fun resolveFailure(
    id: Long,
    claimToken: String,
    outcome: FailureOutcome,
): ClaimTransitionResult =
    transitionResult(
        when (outcome) {
            is FailureOutcome.Retry ->
                db.update(
                    """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                       SET status = 'RETRY', attempt_count = attempt_count + 1,
                           next_attempt_at = ?, last_error = ?,
                           claim_token = NULL, claimed_at = NULL, updated_at = ?
                       WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'""",
                    listOf(outcome.nextAttemptAt, outcome.reason, clock(), id, claimToken),
                )

            is FailureOutcome.PermanentFailure ->
                db.update(
                    """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                       SET status = 'DEAD', attempt_count = attempt_count + 1,
                           last_error = ?,
                           claim_token = NULL, claimed_at = NULL, updated_at = ?
                       WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'""",
                    listOf(outcome.reason, clock(), id, claimToken),
                )

            is FailureOutcome.RejectedBeforeAttempt ->
                db.update(
                    """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                       SET status = 'DEAD',
                           last_error = ?,
                           claim_token = NULL, claimed_at = NULL, updated_at = ?
                       WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'""",
                    listOf(outcome.reason, clock(), id, claimToken),
                )
        },
    )

override fun requeueClaim(
    id: Long,
    claimToken: String,
    nextAttemptAt: Long,
    reason: String?,
): ClaimTransitionResult =
    transitionResult(
        db.update(
            """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
               SET status = 'RETRY',
                   next_attempt_at = ?, last_error = ?,
                   claim_token = NULL, claimed_at = NULL, updated_at = ?
               WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'""",
            listOf(nextAttemptAt, reason, clock(), id, claimToken),
        ),
    )

override fun renewClaim(
    id: Long,
    claimToken: String,
): ClaimTransitionResult {
    val now = clock()
    return transitionResult(
        db.update(
            """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
               SET claimed_at = ?, updated_at = ?
               WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'""",
            listOf(now, now, id, claimToken),
        ),
    )
}
```

의미는 다음과 같다.

- `markSent()`는 `last_error`를 비운다.
- `resolveFailure(Retry)`와 `resolveFailure(PermanentFailure)`만 `attempt_count`를 증가시킨다.
- `resolveFailure(RejectedBeforeAttempt)`는 `attempt_count`를 증가시키지 않는다.
- `requeueClaim()`은 소유권을 반납한다.
- `renewClaim()`은 소유권을 유지한 채 lease만 연장한다.

---

## 6. Dispatcher 동작 스냅샷

### 6.1. 현재 `processEntry()` 의미

현재 dispatcher는 pre-attempt failure와 post-attempt failure를 명확히 분리하고, pre-attempt에서도 deterministic reject와 unexpected local exception을 구분한다.

```kotlin
private suspend fun processEntry(entry: ClaimedDelivery) {
    var attemptStarted = false
    var heartbeatJob: Job? = null
    try {
        val url = config.webhookEndpointFor(entry.route).takeIf { it.isNotBlank() }
        if (url.isNullOrBlank()) {
            resolveRejectedBeforeAttempt(entry, "no webhook URL configured for route=${entry.route}")
            return
        }

        if (shuttingDown) {
            releaseClaimIfOutstanding(
                entry = entry,
                nextAttemptAt = clock(),
                reason = "dispatcher shutdown before delivery attempt",
            )
            return
        }

        val request =
            requestBuilder(
                WebhookDelivery(
                    url = url,
                    messageId = entry.messageId,
                    route = entry.route,
                    payloadJson = entry.payloadJson,
                    attempt = entry.failedAttemptCount,
                ),
            )

        attemptStarted = true
        heartbeatJob = coroutineScope.launchClaimHeartbeat(entry)
        val statusCode =
            try {
                withTimeout(deliveryPolicy.deliveryTimeoutMs) {
                    requestExecutor(request, url)
                }
            } catch (timeout: TimeoutCancellationException) {
                scheduleRetryOrDead(entry, "delivery timeout after ${deliveryPolicy.deliveryTimeoutMs}ms")
                return
            }

        when {
            statusCode in 200..299 -> observeResult("markSent", entry, store.markSent(entry.id, entry.claimToken))
            shouldRetryStatus(statusCode) -> scheduleRetryOrDead(entry, "status=$statusCode")
            else -> observeResult("resolveFailure", entry, store.resolveFailure(entry.id, entry.claimToken, FailureOutcome.PermanentFailure("status=$statusCode")))
        }
    } catch (cancelled: CancellationException) {
        if (!attemptStarted) {
            releaseClaimIfOutstanding(
                entry = entry,
                nextAttemptAt = clock(),
                reason = "dispatcher shutdown before delivery attempt",
            )
        } else if (outstandingClaims.remove(entry.id, entry)) {
            scheduleRetryOrDead(entry, "delivery cancelled after attempt start")
        }
        throw cancelled
    } catch (error: DeterministicPreAttemptRejectException) {
        if (!attemptStarted) {
            resolveRejectedBeforeAttempt(entry, "invalid local delivery input before attempt: ${error.message}")
        } else {
            scheduleRetryOrDead(entry, error.message)
        }
    } catch (error: Exception) {
        scheduleRetryOrDead(
            entry,
            if (!attemptStarted) {
                "unexpected local failure before delivery attempt: ${error.message}"
            } else {
                error.message
            },
        )
    } finally {
        withContext(NonCancellable) {
            heartbeatJob?.cancelAndJoin()
        }
        outstandingClaims.remove(entry.id, entry)
    }
}
```

### 6.2. 현재 dispatcher 규칙

| 상황 | 현재 동작 |
|---|---|
| URL 미설정 | `resolveFailure(RejectedBeforeAttempt)` |
| 셧다운으로 HTTP 시도 전 중단 | `requeueClaim()` |
| 큐 포화로 route partition enqueue 실패 | `requeueClaim()` |
| deterministic request 생성 실패 (`DeterministicPreAttemptRejectException`) | `resolveFailure(RejectedBeforeAttempt)` |
| unexpected local exception before attempt | `scheduleRetryOrDead()` |
| HTTP timeout | `scheduleRetryOrDead()` |
| HTTP 2xx | `markSent()` |
| HTTP 408/429/5xx | `scheduleRetryOrDead()` |
| HTTP 4xx 비재시도 상태 | `resolveFailure(PermanentFailure)` |
| HTTP 시도 후 cancellation | `scheduleRetryOrDead()` |

### 6.3. heartbeat

현재 heartbeat는 전송 중 만료 lease를 방지하기 위해 별도 coroutine으로 동작한다.

```kotlin
private fun CoroutineScope.launchClaimHeartbeat(entry: ClaimedDelivery): Job =
    launch {
        while (isActive) {
            delay(deliveryPolicy.claimHeartbeatIntervalMs)
            val result =
                observeResult(
                    "renewClaim",
                    entry,
                    store.renewClaim(entry.id, entry.claimToken),
                )
            if (result == ClaimTransitionResult.STALE_CLAIM) {
                return@launch
            }
        }
    }
```

### 6.4. timeout cancellation 근거

`WebhookDeliveryClient.execute()`는 coroutine cancellation과 실제 HTTP call 취소를 연결한다.

```kotlin
suspend fun execute(
    request: Request,
    webhookUrl: String,
): Int =
    suspendCancellableCoroutine { continuation ->
        val call = clientFactory.clientFor(webhookUrl).newCall(request)
        continuation.invokeOnCancellation {
            call.cancel()
        }
        call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (!continuation.isActive) {
                        return
                    }
                    continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    response.use {
                        if (!continuation.isActive) {
                            return
                        }
                        continuation.resume(it.code)
                    }
                }
            },
        )
    }
```

즉 `withTimeout(...)`로 coroutine이 취소되면, underlying `Call.cancel()`도 함께 호출된다.

### 6.5. deterministic reject taxonomy

deterministic한 request 생성 입력 오류는 일반 런타임 예외 타입이 아니라 도메인 예외로 승격한다.

```kotlin
internal class DeterministicPreAttemptRejectException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class WebhookRequestFactory(
    private val config: ActiveSecretProvider,
) {
    fun create(delivery: WebhookDelivery): Request =
        try {
            Request
                .Builder()
                .url(delivery.url)
                .post(delivery.payloadJson.toRequestBody(APPLICATION_JSON.toMediaType()))
                .header(HEADER_IRIS_MESSAGE_ID, delivery.messageId)
                .header(HEADER_IRIS_ROUTE, delivery.route)
                .apply {
                    val webhookToken = config.activeOutboundWebhookToken()
                    if (webhookToken.isNotBlank()) {
                        header(HEADER_IRIS_TOKEN, webhookToken)
                    }
                }.build()
        } catch (error: IllegalArgumentException) {
            throw DeterministicPreAttemptRejectException(
                "invalid webhook request input for route=${delivery.route}: ${error.message}",
                error,
            )
        }
}
```

즉 dispatcher는 더 이상 `IllegalArgumentException` 일반형에 의미를 기대지 않고, deterministic reject라는 도메인 계약만 보고 분기한다.

---

## 7. Policy 정합성

현재 `WebhookDeliveryPolicy`는 timeout / lease / recovery 관계를 코드 수준에서 강제한다.

```kotlin
internal data class WebhookDeliveryPolicy(
    val maxDeliveryAttempts: Int = DEFAULT_MAX_DELIVERY_ATTEMPTS,
    val maxClaimBatchSize: Int = 64,
    val pollIntervalMs: Long = 200L,
    val partitionQueueCapacity: Int = 64,
    val claimRecoveryIntervalMs: Long = 30_000L,
    val claimExpirationMs: Long = 60_000L,
    val deliveryTimeoutMs: Long = 20_000L,
    val claimHeartbeatIntervalMs: Long = 10_000L,
) {
    companion object {
        const val DEFAULT_MAX_DELIVERY_ATTEMPTS = 6
        private const val CLAIM_TIMEOUT_SAFETY_MARGIN_MS = 1_000L
    }

    init {
        require(maxDeliveryAttempts > 0) { "maxDeliveryAttempts must be positive" }
        require(maxClaimBatchSize > 0) { "maxClaimBatchSize must be positive" }
        require(pollIntervalMs > 0L) { "pollIntervalMs must be positive" }
        require(partitionQueueCapacity > 0) { "partitionQueueCapacity must be positive" }
        require(claimRecoveryIntervalMs > 0L) { "claimRecoveryIntervalMs must be positive" }
        require(claimExpirationMs > 0L) { "claimExpirationMs must be positive" }
        require(deliveryTimeoutMs > 0L) { "deliveryTimeoutMs must be positive" }
        require(claimHeartbeatIntervalMs > 0L) { "claimHeartbeatIntervalMs must be positive" }
        require(claimRecoveryIntervalMs <= claimExpirationMs) {
            "claimRecoveryIntervalMs must not exceed claimExpirationMs"
        }
        require(claimHeartbeatIntervalMs < claimExpirationMs) {
            "claimHeartbeatIntervalMs must be less than claimExpirationMs"
        }
        require(claimExpirationMs > deliveryTimeoutMs + CLAIM_TIMEOUT_SAFETY_MARGIN_MS) {
            "claimExpirationMs must exceed deliveryTimeoutMs with safety margin"
        }
    }
}
```

현재 의미는 다음과 같다.

- timeout/heartbeat 자체는 음수나 0이 될 수 없다.
- recovery 주기가 expiration보다 길면 안 된다.
- heartbeat 주기는 expiration보다 짧아야 한다.
- expiration은 delivery timeout보다 충분히 길어야 한다.

즉, "전송은 아직 진행 중인데 claim lease가 먼저 만료되는" 설정을 생성 시점에 막는다.

---

## 8. 검증 현황

현재 관련 테스트 파일과 범위는 아래와 같다.

| 파일 | 현재 테스트 수 | 핵심 검증 |
|---|---:|---|
| `SqliteWebhookDeliveryStoreTest` | 17 | 상태 전이, stale token, `last_error` 정리, row별 claim token, `renewClaim`, recovery |
| `WebhookOutboxDispatcherTest` | 15 | 셧다운 race, deterministic pre-attempt reject, unexpected pre-attempt retry, timeout, heartbeat, observer, max attempts |
| `WebhookDeliveryPolicyTest` | 5 | positive guard + 상대 guard 메시지 직접 검증 |
| `WebhookDeliveryClientTest` | 3 | status 반환, IO 예외 전파, timeout cancellation 시 socket close |
| `WebhookRequestFactoryTest` | 3 | header/token 구성, malformed URL의 deterministic reject wrapping |

현재 검증 포인트는 아래를 덮는다.

- `RejectedBeforeAttempt`는 `attempt_count`를 올리지 않음
- `requeueClaim()`은 `attempt_count`를 보존함
- stale token이면 `markSent`, `resolveFailure`, `requeueClaim`, `renewClaim` 모두 `STALE_CLAIM`
- `markSent()`는 이전 retry의 `last_error`를 정리함
- malformed request input은 `DeterministicPreAttemptRejectException`으로 고정 래핑됨
- deterministic request 생성 실패는 `RejectedBeforeAttempt`로 끝남
- unexpected pre-attempt local exception은 silent dead-letter가 아니라 retry 경로로 감
- unexpected pre-attempt local exception은 retry budget을 소비함
- timeout after attempt start는 `requeueClaim()`이 아니라 `Retry`
- long-running delivery는 `renewClaim()` heartbeat를 보냄
- `ClaimTransitionObserver`는 stale transition을 관측할 수 있음
- policy guard는 양수 조건과 상대 조건을 예외 메시지까지 고정 검증함
- timeout cancellation은 underlying HTTP socket close까지 검증함

검증 커맨드:

```bash
./gradlew :app:testDebugUnitTest --tests "party.qwer.iris.persistence.SqliteWebhookDeliveryStoreTest"
./gradlew :app:testDebugUnitTest --tests "party.qwer.iris.delivery.webhook.WebhookDeliveryPolicyTest"
./gradlew :app:testDebugUnitTest --tests "party.qwer.iris.delivery.webhook.WebhookOutboxDispatcherTest"
./gradlew :app:testDebugUnitTest
```

---

## 9. 현재 결론

현재 상태 전이 계약은 아래처럼 정리된다.

1. `resolveFailure()`는 "실패를 시도로 카운트할지"를 outcome 타입으로 결정한다.
2. `requeueClaim()`은 시스템 사유 전용이며, HTTP 시도 전에만 호출해야 한다.
3. `renewClaim()`은 장시간 전송 중 claim lease를 유지하기 위한 heartbeat다.
4. `ClaimTransitionResult`는 stale claim을 호출자에게 직접 노출한다.
5. dispatcher는 deterministic pre-attempt reject, unexpected pre-attempt local exception, post-attempt delivery failure를 더 이상 같은 의미로 섞지 않는다.
6. unexpected pre-attempt local exception은 deterministic reject가 아니라 transient local failure로 간주하며 retry budget을 소비한다.

즉, 현재 구현은 "상태 이름"보다 "의미상 언제 attempt를 증가시키는가"를 우선하는 형태로 정리돼 있다.

---

## 10. 남은 후속 항목

| 항목 | 우선도 | 현재 상태 |
|---|---|---|
| `STALE_CLAIM` 메트릭 수집 | 낮음 | 현재는 observer + warning 로그까지만 있음 |
| `processEntry()` 책임 분리 | 낮음 | 현재 단일 메서드 안에 분기 로직이 모여 있음 |

명시적으로 종료된 항목은 다시 후속으로 두지 않는다.

- row별 `claimToken`
- `failedAttemptCount`
- `ClaimTransitionResult`
- `markSent()`의 `last_error = NULL`
- 셧다운 race 수정
- claim heartbeat / timeout 정합성
- `WebhookDeliveryClient.execute()`의 cancellation-aware timeout 처리
- deterministic pre-attempt reject와 unexpected pre-attempt retry의 분리
- URL 미설정 시 `RejectedBeforeAttempt -> DEAD` 정책 확정 (`BLOCKED_CONFIG` 미도입)

---

## 11. 운영 전제

- 현재 영속 outbox는 at-least-once delivery다.
- downstream receiver는 `messageId` 기준 idempotent 처리가 반드시 가능해야 한다.
- 이 전제가 없으면 timeout, recovery, stale claim 시나리오에서 중복 처리 리스크를 제거할 수 없다.
- 현재 제품 정책은 URL 미설정 항목을 자동 재전송하지 않으며, 재기동 후에도 복구하지 않는다.
- 따라서 URL 미설정은 계속 `RejectedBeforeAttempt -> DEAD`로 처리하고, `BLOCKED_CONFIG` 상태는 도입하지 않는다.

---

## 12. 구현 비고

- `attempt_count`는 DB 컬럼명이고, 코드에서는 의미를 분명히 하기 위해 `failedAttemptCount`를 사용한다.
- `recoverExpiredClaims()`는 만료된 `CLAIMED` row를 `RETRY`로 되돌린다.
- `renewClaim()`은 recover 경로와 경쟁할 수 있으므로 `STALE_CLAIM`을 정상 결과로 취급한다.
- 현재 `BLOCKED_CONFIG` 상태는 도입하지 않으며, 설정 부재 건은 `RejectedBeforeAttempt -> DEAD`로 종결한다.

---

## 13. 관련 소스

- `app/src/main/java/party/qwer/iris/persistence/WebhookDeliveryStore.kt`
- `app/src/main/java/party/qwer/iris/persistence/SqliteWebhookDeliveryStore.kt`
- `app/src/main/java/party/qwer/iris/persistence/IrisDatabaseSchema.kt`
- `app/src/main/java/party/qwer/iris/delivery/webhook/WebhookDeliveryPolicy.kt`
- `app/src/main/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcher.kt`
- `app/src/main/java/party/qwer/iris/delivery/webhook/ClaimTransitionObserver.kt`
- `app/src/test/java/party/qwer/iris/persistence/SqliteWebhookDeliveryStoreTest.kt`
- `app/src/test/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcherTest.kt`
- `app/src/test/java/party/qwer/iris/delivery/webhook/WebhookDeliveryPolicyTest.kt`
- `app/src/test/java/party/qwer/iris/delivery/webhook/WebhookDeliveryClientTest.kt`
