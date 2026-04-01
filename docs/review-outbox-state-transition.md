# Review: Outbox State Transition API 통합

> **상태**: 종결 — Option B' 채택, R4 지적사항 + R5 의미 버그/개선 전수 해소 (2026-04-01)
>
> **목적**: `WebhookDeliveryStore`의 상태 전이 메서드 4개(`markSent`, `markRetry`, `markDead`, `releaseClaim`)를 통합할지 여부에 대한 설계 결정.
> 외부 리뷰어가 코드를 직접 열지 않아도 판단할 수 있도록 관련 코드 전체를 포함함.

---

## 1. 현행 아키텍처 개요

Iris의 webhook delivery는 두 경로로 나뉜다:

| 경로 | 클래스 | 영속성 | 상태 모델 |
|---|---|---|---|
| 인메모리 직접 전달 | `H2cDispatcher` | 없음 (재시작 시 유실) | `DeliveryOutcome` enum (SUCCESS, DROP, RETRY_LATER) |
| 영속 outbox | `WebhookOutboxDispatcher` | SQLite `webhook_outbox` 테이블 | DB status 컬럼 (PENDING → CLAIMED → SENT/RETRY/DEAD) |

이 리뷰의 대상은 **영속 outbox 경로**의 상태 전이 인터페이스.

---

## 2. 현행 상태 전이 다이어그램

```
                   enqueue (INSERT OR IGNORE)
                          │
                          ▼
                       PENDING
                          │  claimReady()
                          ▼
                       CLAIMED ─────────────────────────┐
                      / │  \    \                        │
           markSent  / markRetry\ markDead  releaseClaim │
                    /    │       \          \             │
                 SENT  RETRY    DEAD      RETRY          │
                         │             (attempt 보존)     │
                         └─ claimReady() ────────────────┘
                
    recoverExpiredClaims(): CLAIMED(만료) → RETRY
```

`CLAIMED`에서 나가는 경로가 4개, 각각 별도 인터페이스 메서드:

| 메서드 | 결과 상태 | attempt_count | 용도 |
|---|---|---|---|
| `markSent` | SENT | 변경 없음 | 전달 성공 |
| `markRetry` | RETRY | +1 | 재시도 가능 실패 |
| `markDead` | DEAD | +1 | 영구 실패 (터미널) |
| `releaseClaim` | RETRY | **변경 없음** | 셧다운/큐 포화 (시도로 안 침) |

---

## 3. 문제 식별

### 3.1. `attempt_count` 의미 불일치

`markDead`가 3곳에서 호출되지만 의미가 다르다:

| 호출 위치 | 상황 | HTTP 시도 여부 | attempt_count +1 적절? |
|---|---|---|---|
| `WebhookOutboxDispatcher.kt:119` | URL 미설정 | X (요청 자체 없음) | **부적절** |
| `WebhookOutboxDispatcher.kt:154` | 비재시도 상태코드 (400, 403 등) | O | 적절 |
| `WebhookOutboxDispatcher.kt:185` | 재시도 소진 | O | 적절 |

URL 미설정 건은 HTTP 요청 자체가 발생하지 않았으므로 `attempt_count`가 0→1로 증가하는 것은 부정확하다.

### 3.2. `markRetry`와 `releaseClaim`의 구분이 시그니처에 드러나지 않음

두 메서드의 파라미터 시그니처가 동일하다:

```kotlin
fun markRetry(id: Long, claimToken: String, nextAttemptAt: Long, reason: String?)
fun releaseClaim(id: Long, claimToken: String, nextAttemptAt: Long, reason: String?)
```

SQL도 `attempt_count = attempt_count + 1` 한 줄만 다르다. 이름으로만 구분되므로 잘못 골라 쓸 위험이 있다.

### 3.3. retry/dead 결정이 호출자에 분산

`scheduleRetryOrDead`에서 retry-vs-dead 결정을 완료한 뒤 결과를 별도 메서드로 분기한다. 스토어 자체는 불변식(예: max attempts 초과 시 반드시 DEAD)을 강제할 수 없다.

---

## 4. 제안 선택지

### Option A: 현행 유지 + `markDead`에 `incrementAttempt` 파라미터 추가

attempt_count 문제만 최소 범위로 수정.

### Option B: `markRetry` + `markDead` → `resolveFailure` 통합 (추천)

실패 후 처리를 sealed class 하나로 통합. `markSent`와 `releaseClaim`은 유지.

### Option C: 4개 전부 `resolveAttempt` 단일 진입점으로 통합

성공/실패/릴리스 모두 하나의 메서드로.

각 선택지의 상세 비교는 [섹션 8](#8-선택지-비교표)에서 다룬다.

---

## 5. 현행 코드 (전체)

### 5.1. DB 스키마

```kotlin
// persistence/IrisDatabaseSchema.kt

object IrisDatabaseSchema {
    const val WEBHOOK_OUTBOX_TABLE = "webhook_outbox"

    private val CREATE_WEBHOOK_OUTBOX =
        """
        CREATE TABLE IF NOT EXISTS $WEBHOOK_OUTBOX_TABLE (
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
        """.trimIndent()

    private val CREATE_WEBHOOK_OUTBOX_INDEX =
        """
        CREATE INDEX IF NOT EXISTS idx_webhook_outbox_ready
        ON $WEBHOOK_OUTBOX_TABLE (status, next_attempt_at, id)
        """.trimIndent()
}
```

### 5.2. 인터페이스 + 데이터 클래스

```kotlin
// persistence/WebhookDeliveryStore.kt

data class PendingWebhookDelivery(
    val messageId: String,
    val roomId: Long,
    val route: String,
    val payloadJson: String,
)

data class ClaimedDelivery(
    val id: Long,
    val messageId: String,
    val roomId: Long,
    val route: String,
    val payloadJson: String,
    val attemptCount: Int,
    val claimToken: String,
)

interface WebhookDeliveryStore : Closeable {
    fun enqueue(delivery: PendingWebhookDelivery): Long

    fun claimReady(limit: Int): List<ClaimedDelivery>

    fun markSent(
        id: Long,
        claimToken: String,
    )

    fun markRetry(
        id: Long,
        claimToken: String,
        nextAttemptAt: Long,
        reason: String?,
    )

    fun releaseClaim(
        id: Long,
        claimToken: String,
        nextAttemptAt: Long,
        reason: String?,
    )

    fun markDead(
        id: Long,
        claimToken: String,
        reason: String?,
    )

    fun recoverExpiredClaims(olderThanMs: Long): Int
}
```

### 5.3. SQLite 구현체

```kotlin
// persistence/SqliteWebhookDeliveryStore.kt

class SqliteWebhookDeliveryStore(
    private val db: SqliteDriver,
    private val clock: () -> Long = System::currentTimeMillis,
) : WebhookDeliveryStore {
    override fun enqueue(delivery: PendingWebhookDelivery): Long =
        db.inImmediateTransaction {
            val now = clock()
            update(
                """INSERT OR IGNORE INTO ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                   (message_id, room_id, route, payload_json, status, attempt_count, next_attempt_at, created_at, updated_at)
                   VALUES (?, ?, ?, ?, 'PENDING', 0, 0, ?, ?)""",
                listOf(delivery.messageId, delivery.roomId, delivery.route, delivery.payloadJson, now, now),
            )
            queryLong(
                "SELECT id FROM ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE} WHERE message_id = ?",
                delivery.messageId,
            )!!
        }

    override fun claimReady(limit: Int): List<ClaimedDelivery> =
        db.inImmediateTransaction {
            val now = clock()
            val claimToken = UUID.randomUUID().toString()
            val readyIds =
                query(
                    """SELECT id FROM ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                       WHERE status IN ('PENDING', 'RETRY') AND next_attempt_at <= ?
                       ORDER BY id LIMIT ?""",
                    listOf(now, limit),
                ) { row -> row.getLong(0) }
            if (readyIds.isEmpty()) {
                return@inImmediateTransaction emptyList()
            }

            val placeholders = readyIds.joinToString(",") { "?" }
            update(
                """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                   SET status = 'CLAIMED', claim_token = ?, claimed_at = ?, updated_at = ?
                   WHERE id IN ($placeholders)""",
                listOf(claimToken, now, now) + readyIds,
            )

            query(
                """SELECT id, message_id, room_id, route, payload_json, attempt_count
                   FROM ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                   WHERE id IN ($placeholders) ORDER BY id""",
                readyIds,
            ) { row ->
                ClaimedDelivery(
                    id = row.getLong(0),
                    messageId = row.getString(1),
                    roomId = row.getLong(2),
                    route = row.getString(3),
                    payloadJson = row.getString(4),
                    attemptCount = row.getInt(5),
                    claimToken = claimToken,
                )
            }
        }

    override fun markSent(
        id: Long,
        claimToken: String,
    ) {
        db.update(
            """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
               SET status = 'SENT', claim_token = NULL, claimed_at = NULL, updated_at = ?
               WHERE id = ? AND claim_token = ?""",
            listOf(clock(), id, claimToken),
        )
    }

    override fun markRetry(
        id: Long,
        claimToken: String,
        nextAttemptAt: Long,
        reason: String?,
    ) {
        db.update(
            """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
               SET status = 'RETRY', attempt_count = attempt_count + 1,
                   next_attempt_at = ?, last_error = ?, claim_token = NULL, claimed_at = NULL, updated_at = ?
               WHERE id = ? AND claim_token = ?""",
            listOf(nextAttemptAt, reason, clock(), id, claimToken),
        )
    }

    override fun releaseClaim(
        id: Long,
        claimToken: String,
        nextAttemptAt: Long,
        reason: String?,
    ) {
        db.update(
            """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
               SET status = 'RETRY',
                   next_attempt_at = ?, last_error = ?, claim_token = NULL, claimed_at = NULL, updated_at = ?
               WHERE id = ? AND claim_token = ?""",
            listOf(nextAttemptAt, reason, clock(), id, claimToken),
        )
    }

    override fun markDead(
        id: Long,
        claimToken: String,
        reason: String?,
    ) {
        db.update(
            """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
               SET status = 'DEAD', attempt_count = attempt_count + 1,
                   last_error = ?, claim_token = NULL, claimed_at = NULL, updated_at = ?
               WHERE id = ? AND claim_token = ?""",
            listOf(reason, clock(), id, claimToken),
        )
    }

    override fun recoverExpiredClaims(olderThanMs: Long): Int {
        val cutoff = clock() - olderThanMs
        return db.update(
            """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
               SET status = 'RETRY', claim_token = NULL, claimed_at = NULL, updated_at = ?
               WHERE status = 'CLAIMED' AND claimed_at <= ?""",
            listOf(clock(), cutoff),
        )
    }

    override fun close() {}
}
```

### 5.4. Outbox 디스패처 (유일한 소비자)

```kotlin
// delivery/webhook/WebhookOutboxDispatcher.kt

internal class WebhookOutboxDispatcher(
    private val config: ConfigProvider,
    private val store: WebhookDeliveryStore,
    transportOverride: String? = null,
    private val partitionCount: Int = 4,
    private val deliveryPolicy: WebhookDeliveryPolicy = WebhookDeliveryPolicy(),
    private val backoffDelayProvider: (Int) -> Long = ::nextBackoffDelayMs,
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {
    private val scopeJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(scopeJob + dispatcher)
    private val clientFactory = WebhookHttpClientFactory(resolveWebhookTransport(transportOverride), okhttp3.Dispatcher(), okhttp3.ConnectionPool())
    private val requestFactory = WebhookRequestFactory(config)
    private val deliveryClient = WebhookDeliveryClient(clientFactory)
    private val routePartitions = ConcurrentHashMap<String, RoutePartitions>()
    private val outstandingClaims = ConcurrentHashMap<Long, ClaimedDelivery>()

    @Volatile
    private var pollingJob: Job? = null

    @Volatile
    private var nextClaimRecoveryAtMs: Long = 0L

    @Volatile
    private var shuttingDown: Boolean = false

    fun start() {
        if (pollingJob?.isActive == true) {
            return
        }
        shuttingDown = false
        recoverExpiredClaimsNow()
        pollingJob =
            coroutineScope.launch {
                while (isActive) {
                    if (shuttingDown) {
                        break
                    }
                    recoverExpiredClaimsIfDue()
                    pumpReadyEntries()
                    delay(deliveryPolicy.pollIntervalMs)
                }
            }
    }

    private fun recoverExpiredClaimsIfDue() {
        val now = clock()
        if (now < nextClaimRecoveryAtMs) {
            return
        }
        recoverExpiredClaimsNow(now)
    }

    private fun recoverExpiredClaimsNow(now: Long = clock()) {
        store.recoverExpiredClaims(olderThanMs = deliveryPolicy.claimExpirationMs)
        nextClaimRecoveryAtMs = now + deliveryPolicy.claimRecoveryIntervalMs
    }

    private suspend fun pumpReadyEntries() {
        val claimed = store.claimReady(deliveryPolicy.maxClaimBatchSize)
        claimed.forEach { entry ->
            outstandingClaims[entry.id] = entry
            val partition =
                routePartitions
                    .computeIfAbsent(entry.route, ::createRoutePartitions)
                    .partitions[partitionIndexForRoom(entry.roomId, partitionCount)]
            val sendResult = partition.channel.trySend(entry)
            if (sendResult.isFailure) {
                releaseClaimIfOutstanding(
                    entry = entry,
                    nextAttemptAt = clock() + deliveryPolicy.pollIntervalMs,
                    reason = "partition queue saturated: route=${entry.route}, partition=${partition.index}",
                )
            }
        }
    }

    private fun createRoutePartitions(route: String): RoutePartitions =
        RoutePartitions(
            route = route,
            partitions =
                List(partitionCount.coerceAtLeast(1)) { index ->
                    val channel = Channel<ClaimedDelivery>(deliveryPolicy.partitionQueueCapacity)
                    val job =
                        coroutineScope.launch {
                            for (entry in channel) {
                                processEntry(entry)
                            }
                        }
                    PartitionChannel(index = index, channel = channel, job = job)
                },
        )

    // =====================================================================
    // processEntry: CLAIMED 상태의 entry를 처리하고 상태를 전이시키는 핵심 메서드
    // markSent / markDead / scheduleRetryOrDead 호출이 여기서 분기됨
    // =====================================================================
    private suspend fun processEntry(entry: ClaimedDelivery) {
        try {
            val url = config.webhookEndpointFor(entry.route).takeIf { it.isNotBlank() }
            if (url.isNullOrBlank()) {
                // [문제 3.1] URL 미설정 — HTTP 시도 없이 markDead → attempt_count +1 (부적절)
                store.markDead(entry.id, entry.claimToken, "no webhook URL configured for route=${entry.route}")
                return
            }

            val request =
                requestFactory.create(
                    WebhookDelivery(
                        url = url,
                        messageId = entry.messageId,
                        route = entry.route,
                        payloadJson = entry.payloadJson,
                        attempt = entry.attemptCount,
                    ),
                )

            val statusCode =
                try {
                    deliveryClient.execute(request, url)
                } catch (error: Exception) {
                    if (shuttingDown) {
                        releaseClaimIfOutstanding(entry = entry, nextAttemptAt = clock(), reason = "dispatcher shutdown")
                        return
                    }
                    scheduleRetryOrDead(entry, error.message)
                    return
                }

            if (shuttingDown) {
                releaseClaimIfOutstanding(entry = entry, nextAttemptAt = clock(), reason = "dispatcher shutdown")
                return
            }

            when {
                statusCode in 200..299 -> store.markSent(entry.id, entry.claimToken)
                shouldRetryStatus(statusCode) -> scheduleRetryOrDead(entry, "status=$statusCode")
                else -> store.markDead(entry.id, entry.claimToken, "status=$statusCode")
            }
        } catch (cancelled: CancellationException) {
            releaseClaimIfOutstanding(entry = entry, nextAttemptAt = clock(), reason = "dispatcher shutdown")
            throw cancelled
        } finally {
            outstandingClaims.remove(entry.id, entry)
        }
    }

    // =====================================================================
    // scheduleRetryOrDead: retry-vs-dead 결정이 여기서 완료된 뒤 별도 메서드로 분기됨
    // [문제 3.3] 스토어가 이 불변식을 강제할 수 없음
    // =====================================================================
    private fun scheduleRetryOrDead(
        entry: ClaimedDelivery,
        reason: String?,
    ) {
        when (
            val retrySchedule =
                nextDeliveryRetrySchedule(
                    attempt = entry.attemptCount,
                    maxDeliveryAttempts = deliveryPolicy.maxDeliveryAttempts,
                    backoffDelayProvider = backoffDelayProvider,
                )
        ) {
            is DeliveryRetrySchedule.RetryAttempt ->
                store.markRetry(
                    id = entry.id,
                    claimToken = entry.claimToken,
                    nextAttemptAt = clock() + retrySchedule.delayMs,
                    reason = reason,
                )

            is DeliveryRetrySchedule.Exhausted ->
                store.markDead(
                    id = entry.id,
                    claimToken = entry.claimToken,
                    reason = reason ?: "delivery attempts exhausted",
                )
        }
    }

    override fun close() {
        runBlocking { closeSuspend() }
    }

    suspend fun closeSuspend() {
        shuttingDown = true
        val job = pollingJob
        pollingJob = null
        job?.cancelAndJoin()
        routePartitions.values.forEach { route ->
            route.partitions.forEach { partition ->
                partition.channel.close()
            }
        }
        releaseOutstandingClaims()
        routePartitions.values.forEach { route ->
            route.partitions.forEach { partition ->
                partition.job.cancelAndJoin()
            }
        }
        scopeJob.cancelAndJoin()
        clientFactory
            .clientFor("http://127.0.0.1")
            .dispatcher.executorService
            .shutdownNow()
        clientFactory.clientFor("http://127.0.0.1").connectionPool.evictAll()
        store.close()
    }

    private fun releaseOutstandingClaims() {
        outstandingClaims.values.toList().forEach { entry ->
            releaseClaimIfOutstanding(entry = entry, nextAttemptAt = clock(), reason = "dispatcher shutdown")
        }
    }

    private fun releaseClaimIfOutstanding(
        entry: ClaimedDelivery,
        nextAttemptAt: Long,
        reason: String,
    ): Boolean {
        if (!outstandingClaims.remove(entry.id, entry)) {
            return false
        }
        store.releaseClaim(
            id = entry.id,
            claimToken = entry.claimToken,
            nextAttemptAt = nextAttemptAt,
            reason = reason,
        )
        return true
    }

    private data class RoutePartitions(
        val route: String,
        val partitions: List<PartitionChannel>,
    )

    private data class PartitionChannel(
        val index: Int,
        val channel: Channel<ClaimedDelivery>,
        val job: Job,
    )
}
```

### 5.5. 딜리버리 정책 + 재시도 스케줄

```kotlin
// delivery/webhook/WebhookDeliveryPolicy.kt

internal data class WebhookDeliveryPolicy(
    val maxDeliveryAttempts: Int = DEFAULT_MAX_DELIVERY_ATTEMPTS,
    val maxClaimBatchSize: Int = 64,
    val pollIntervalMs: Long = 200L,
    val partitionQueueCapacity: Int = 64,
    val claimRecoveryIntervalMs: Long = 30_000L,
    val claimExpirationMs: Long = 60_000L,
) {
    companion object {
        const val DEFAULT_MAX_DELIVERY_ATTEMPTS = 6
    }

    init {
        require(maxDeliveryAttempts > 0) { "maxDeliveryAttempts must be positive" }
        require(maxClaimBatchSize > 0) { "maxClaimBatchSize must be positive" }
        require(pollIntervalMs > 0L) { "pollIntervalMs must be positive" }
        require(partitionQueueCapacity > 0) { "partitionQueueCapacity must be positive" }
        require(claimRecoveryIntervalMs > 0L) { "claimRecoveryIntervalMs must be positive" }
        require(claimExpirationMs > 0L) { "claimExpirationMs must be positive" }
    }
}
```

```kotlin
// delivery/webhook/DeliveryRetryPolicy.kt

private const val MAX_BACKOFF_EXPONENT = 5
private const val MAX_BACKOFF_DELAY_MS = 30_000L
private const val MAX_BACKOFF_JITTER_MS = 500L

internal sealed interface DeliveryRetrySchedule {
    data class RetryAttempt(
        val nextAttempt: Int,
        val delayMs: Long,
    ) : DeliveryRetrySchedule

    data object Exhausted : DeliveryRetrySchedule
}

internal fun nextDeliveryRetrySchedule(
    attempt: Int,
    maxDeliveryAttempts: Int = WebhookDeliveryPolicy.DEFAULT_MAX_DELIVERY_ATTEMPTS,
    backoffDelayProvider: (Int) -> Long = ::nextBackoffDelayMs,
): DeliveryRetrySchedule =
    if (attempt < maxDeliveryAttempts - 1) {
        DeliveryRetrySchedule.RetryAttempt(
            nextAttempt = attempt + 1,
            delayMs = backoffDelayProvider(attempt),
        )
    } else {
        DeliveryRetrySchedule.Exhausted
    }

internal fun nextBackoffDelayMs(attempt: Int): Long =
    nextBackoffDelayMs(attempt) {
        Random.nextLong(0, MAX_BACKOFF_JITTER_MS + 1)
    }

internal fun nextBackoffDelayMs(
    attempt: Int,
    jitterProvider: () -> Long,
): Long {
    val cappedAttempt = attempt.coerceAtMost(MAX_BACKOFF_EXPONENT)
    val baseDelay = 1_000L shl cappedAttempt
    val boundedDelay = baseDelay.coerceAtMost(MAX_BACKOFF_DELAY_MS)
    return boundedDelay + jitterProvider()
}

internal fun shouldRetryStatus(statusCode: Int): Boolean =
    statusCode == 408 || statusCode == 429 || statusCode >= 500
```

### 5.6. 라우팅 게이트웨이 (enqueue 호출처)

```kotlin
// delivery/webhook/RoutingGateway.kt

interface RoutingGateway : Closeable {
    fun route(command: RoutingCommand): RoutingResult
}

internal class OutboxRoutingGateway(
    private val config: ConfigProvider,
    private val deliveryStore: WebhookDeliveryStore,
) : RoutingGateway {
    override fun route(command: RoutingCommand): RoutingResult {
        val resolved = resolveWebhookDelivery(command, config) ?: return RoutingResult.SKIPPED
        deliveryStore.enqueue(
            PendingWebhookDelivery(
                messageId = resolved.messageId,
                roomId = resolved.roomId,
                route = resolved.route,
                payloadJson = resolved.payloadJson,
            ),
        )
        return RoutingResult.ACCEPTED
    }

    override fun close() {
        deliveryStore.close()
    }
}
```

### 5.7. H2cDispatcher (참고: 인메모리 경로의 outcome 모델)

```kotlin
// delivery/webhook/H2cDispatcher.kt (상태 전이 관련 부분만 발췌)

// H2c는 DB 상태 전이 없이 enum으로 outcome을 분류한다
private enum class DeliveryOutcome {
    SUCCESS,
    DROP,
    RETRY_LATER,
}

private suspend fun processQueuedDelivery(delivery: QueuedDelivery) {
    val routeState = routeDispatchers.get(delivery.route)
    val outcome =
        try {
            dispatchAttempt(delivery, delivery.attempt)
        } catch (e: Exception) {
            DeliveryOutcome.RETRY_LATER
        }

    when (outcome) {
        DeliveryOutcome.SUCCESS,
        DeliveryOutcome.DROP,
        -> {
            routeState.queuedMessageIds.remove(delivery.messageId)
        }

        DeliveryOutcome.RETRY_LATER -> {
            when (val retrySchedule = nextDeliveryRetrySchedule(
                attempt = delivery.attempt,
                maxDeliveryAttempts = maxDeliveryAttempts,
                backoffDelayProvider = backoffDelayProvider,
            )) {
                is DeliveryRetrySchedule.RetryAttempt -> {
                    scheduleRetry(delivery.copy(attempt = retrySchedule.nextAttempt), retrySchedule.delayMs)
                }
                is DeliveryRetrySchedule.Exhausted -> {
                    routeState.queuedMessageIds.remove(delivery.messageId)
                }
            }
        }
    }
}

private fun classifyResponse(
    delivery: QueuedDelivery,
    attemptLabel: String,
    statusCode: Int,
): DeliveryOutcome {
    if (statusCode in 200..299) return DeliveryOutcome.SUCCESS
    if (!shouldRetryStatus(statusCode)) return DeliveryOutcome.DROP
    return DeliveryOutcome.RETRY_LATER
}
```

---

## 6. 테스트 코드 (전체)

### 6.1. SqliteWebhookDeliveryStoreTest

```kotlin
// persistence/SqliteWebhookDeliveryStoreTest.kt

class SqliteWebhookDeliveryStoreTest {
    @Test
    fun `enqueue then claim then markSent completes delivery lifecycle`() {
        val (helper, store) = createStore()

        helper.use {
            store.use {
                val delivery =
                    PendingWebhookDelivery(
                        messageId = "message-1",
                        roomId = 100L,
                        route = "default",
                        payloadJson = "{\"text\":\"hello\"}",
                    )

                val id = store.enqueue(delivery)
                val claimed = store.claimReady(limit = 10)

                assertEquals(1, claimed.size)
                assertEquals(
                    ClaimedDelivery(
                        id = id,
                        messageId = delivery.messageId,
                        roomId = delivery.roomId,
                        route = delivery.route,
                        payloadJson = delivery.payloadJson,
                        attemptCount = 0,
                        claimToken = claimed.single().claimToken,
                    ),
                    claimed.single(),
                )

                store.markSent(id, claimed.single().claimToken)

                assertTrue(store.claimReady(limit = 10).isEmpty())
            }
        }
    }

    @Test
    fun `enqueue with duplicate messageId returns existing id`() {
        val (helper, store) = createStore()

        helper.use {
            store.use {
                val firstId =
                    store.enqueue(
                        PendingWebhookDelivery(
                            messageId = "message-1",
                            roomId = 100L,
                            route = "default",
                            payloadJson = "{\"text\":\"hello\"}",
                        ),
                    )

                val secondId =
                    store.enqueue(
                        PendingWebhookDelivery(
                            messageId = "message-1",
                            roomId = 200L,
                            route = "other",
                            payloadJson = "{\"text\":\"ignored\"}",
                        ),
                    )

                assertEquals(firstId, secondId)
            }
        }
    }

    @Test
    fun `markRetry increments attempt and delays next claim`() {
        var now = 1000L
        val (helper, store) = createStore(clock = { now })

        helper.use {
            store.use {
                store.enqueue(
                    PendingWebhookDelivery(
                        messageId = "message-1",
                        roomId = 100L,
                        route = "default",
                        payloadJson = "{\"text\":\"hello\"}",
                    ),
                )

                val firstClaim = store.claimReady(limit = 1).single()
                store.markRetry(
                    id = firstClaim.id,
                    claimToken = firstClaim.claimToken,
                    nextAttemptAt = 6000L,
                    reason = "temporary failure",
                )

                now = 3000L
                assertTrue(store.claimReady(limit = 10).isEmpty())

                now = 6000L
                val secondClaim = store.claimReady(limit = 1).single()
                assertEquals(1, secondClaim.attemptCount)
                assertEquals(firstClaim.id, secondClaim.id)
            }
        }
    }

    @Test
    fun `releaseClaim preserves attempt count and makes entry immediately claimable`() {
        var now = 1000L
        val (helper, store) = createStore(clock = { now })

        helper.use {
            store.use {
                store.enqueue(
                    PendingWebhookDelivery(
                        messageId = "message-1",
                        roomId = 100L,
                        route = "default",
                        payloadJson = "{\"text\":\"hello\"}",
                    ),
                )

                val firstClaim = store.claimReady(limit = 1).single()
                store.releaseClaim(
                    id = firstClaim.id,
                    claimToken = firstClaim.claimToken,
                    nextAttemptAt = now,
                    reason = "graceful shutdown",
                )

                val reclaimed = store.claimReady(limit = 1).single()
                assertEquals(firstClaim.id, reclaimed.id)
                assertEquals(0, reclaimed.attemptCount)
            }
        }
    }

    @Test
    fun `markDead increments attempt count and prevents further claim`() {
        val (helper, store) = createStore()

        helper.use {
            store.use {
                store.enqueue(
                    PendingWebhookDelivery(
                        messageId = "message-1",
                        roomId = 100L,
                        route = "default",
                        payloadJson = "{\"text\":\"hello\"}",
                    ),
                )

                val claim = store.claimReady(limit = 1).single()
                store.markDead(claim.id, claim.claimToken, "permanent failure")

                assertTrue(store.claimReady(limit = 10).isEmpty())
                assertEquals(
                    1L,
                    helper.queryLong(
                        "SELECT attempt_count FROM ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE} WHERE id = ?",
                        claim.id,
                    ),
                )
            }
        }
    }

    @Test
    fun `stale claimToken cannot overwrite a newer claim`() {
        var now = 1000L
        val (helper, store) = createStore(clock = { now })

        helper.use {
            store.use {
                store.enqueue(
                    PendingWebhookDelivery(
                        messageId = "message-1",
                        roomId = 100L,
                        route = "default",
                        payloadJson = "{\"text\":\"hello\"}",
                    ),
                )

                val firstClaim = store.claimReady(limit = 1).single()
                now = 32000L
                assertEquals(1, store.recoverExpiredClaims(olderThanMs = 30000L))

                val secondClaim = store.claimReady(limit = 1).single()

                store.markSent(firstClaim.id, firstClaim.claimToken)

                assertTrue(store.claimReady(limit = 10).isEmpty())

                store.markSent(secondClaim.id, secondClaim.claimToken)
                assertTrue(store.claimReady(limit = 10).isEmpty())
            }
        }
    }

    @Test
    fun `recoverExpiredClaims resets stale CLAIMED entries to RETRY`() {
        var now = 1000L
        val (helper, store) = createStore(clock = { now })

        helper.use {
            store.use {
                repeat(2) { index ->
                    store.enqueue(
                        PendingWebhookDelivery(
                            messageId = "message-${index + 1}",
                            roomId = (index + 1).toLong(),
                            route = "default",
                            payloadJson = "{\"index\":${index + 1}}",
                        ),
                    )
                }

                val firstClaims = store.claimReady(limit = 10)
                assertEquals(2, firstClaims.size)

                now = 32000L
                assertEquals(2, store.recoverExpiredClaims(olderThanMs = 30000L))

                val reclaimed = store.claimReady(limit = 10)
                assertEquals(2, reclaimed.size)
                assertEquals(firstClaims.map { it.id }.sorted(), reclaimed.map { it.id }.sorted())
            }
        }
    }

    @Test
    fun `claimReady respects limit parameter`() {
        val (helper, store) = createStore()

        helper.use {
            store.use {
                repeat(5) { index ->
                    store.enqueue(
                        PendingWebhookDelivery(
                            messageId = "message-${index + 1}",
                            roomId = (index + 1).toLong(),
                            route = "default",
                            payloadJson = "{\"index\":${index + 1}}",
                        ),
                    )
                }

                val firstBatch = store.claimReady(limit = 2)
                val secondBatch = store.claimReady(limit = 10)

                assertEquals(2, firstBatch.size)
                assertEquals(3, secondBatch.size)
            }
        }
    }

    @Test
    fun `already claimed entries are excluded from claimReady`() {
        val (helper, store) = createStore()

        helper.use {
            store.use {
                repeat(2) { index ->
                    store.enqueue(
                        PendingWebhookDelivery(
                            messageId = "message-${index + 1}",
                            roomId = (index + 1).toLong(),
                            route = "default",
                            payloadJson = "{\"index\":${index + 1}}",
                        ),
                    )
                }

                val firstClaim = store.claimReady(limit = 1).single()
                val secondClaim = store.claimReady(limit = 10).single()

                assertTrue(firstClaim.id != secondClaim.id)
            }
        }
    }

    @Test
    fun `claimReady returns entries ordered by id`() {
        val (helper, store) = createStore()

        helper.use {
            store.use {
                repeat(5) { index ->
                    store.enqueue(
                        PendingWebhookDelivery(
                            messageId = "message-${index + 1}",
                            roomId = (index + 1).toLong(),
                            route = "default",
                            payloadJson = "{\"index\":${index + 1}}",
                        ),
                    )
                }

                val claims = store.claimReady(limit = 5)

                assertEquals(claims.map { it.id }.sorted(), claims.map { it.id })
            }
        }
    }

    @Test
    fun `markRetry with stale claimToken is silently ignored`() {
        var now = 1000L
        val (helper, store) = createStore(clock = { now })

        helper.use {
            store.use {
                store.enqueue(
                    PendingWebhookDelivery(
                        messageId = "message-1",
                        roomId = 100L,
                        route = "default",
                        payloadJson = "{\"text\":\"hello\"}",
                    ),
                )

                val firstClaim = store.claimReady(limit = 1).single()
                now = 32000L
                assertEquals(1, store.recoverExpiredClaims(olderThanMs = 30000L))

                val secondClaim = store.claimReady(limit = 1).single()
                store.markRetry(
                    id = firstClaim.id,
                    claimToken = firstClaim.claimToken,
                    nextAttemptAt = 60000L,
                    reason = "stale retry",
                )

                store.markSent(secondClaim.id, secondClaim.claimToken)
                assertTrue(store.claimReady(limit = 10).isEmpty())
            }
        }
    }

    private fun createStore(
        clock: () -> Long = System::currentTimeMillis,
    ): Pair<JdbcSqliteHelper, SqliteWebhookDeliveryStore> {
        val helper = JdbcSqliteHelper.inMemory()
        IrisDatabaseSchema.createWebhookOutboxTable(helper)
        val store = SqliteWebhookDeliveryStore(helper, clock)
        return helper to store
    }
}
```

### 6.2. WebhookOutboxDispatcherTest + RecordingWebhookDeliveryStore

```kotlin
// delivery/webhook/WebhookOutboxDispatcherTest.kt

@OptIn(ExperimentalCoroutinesApi::class)
class WebhookOutboxDispatcherTest {
    private val fakeClock = AtomicLong(1_000_000L)

    @Test
    fun `dispatcher retries claims when partition queue is saturated`() {
        val configPath = Files.createTempFile("iris-outbox-dispatcher", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        val port = reservePort()
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:$port/webhook/iris")

        val releaseLatch = CountDownLatch(1)
        val store =
            RecordingWebhookDeliveryStore(
                claimedEntries =
                    listOf(
                        claimedEntry(id = 1L, roomId = 1L, messageId = "msg-1"),
                        claimedEntry(id = 2L, roomId = 1L, messageId = "msg-2"),
                        claimedEntry(id = 3L, roomId = 1L, messageId = "msg-3"),
                    ),
                onRelease = { releaseLatch.countDown() },
            )

        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris") { exchange ->
                    CountDownLatch(1).await(300, TimeUnit.MILLISECONDS)
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }
                executor = Executors.newSingleThreadExecutor()
                start()
            }

        try {
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                transportOverride = "http1",
                partitionCount = 1,
                deliveryPolicy =
                    WebhookDeliveryPolicy(
                        partitionQueueCapacity = 1,
                        pollIntervalMs = 25L,
                        maxClaimBatchSize = 10,
                    ),
                clock = fakeClock::get,
            ).use { dispatcher ->
                dispatcher.start()
                assertTrue(releaseLatch.await(5, TimeUnit.SECONDS))
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)
                ?.shutdownNow()
            Files.deleteIfExists(Path.of(configPath))
        }

        assertTrue(store.releasedIds.isNotEmpty())
        assertTrue(store.releasedIds.any { it in setOf(2L, 3L) })
        assertTrue(store.releaseReasons.any { it.contains("partition queue saturated") })
    }

    @Test
    fun `dispatcher periodically recovers expired claims while running`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val configPath = Files.createTempFile("iris-outbox-dispatcher", ".json").toFile().absolutePath
            val config = ConfigManager(configPath = configPath)
            val store = RecordingWebhookDeliveryStore(claimedEntries = emptyList())
            val recoveryInterval = 10_000L
            val outboxDispatcher =
                WebhookOutboxDispatcher(
                    config = config,
                    store = store,
                    deliveryPolicy =
                        WebhookDeliveryPolicy(
                            pollIntervalMs = 25L,
                            maxClaimBatchSize = 1,
                            claimRecoveryIntervalMs = recoveryInterval,
                        ),
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            try {
                outboxDispatcher.start()
                assertEquals(1, store.recoverCallCount.get())
                runCurrent()
                advanceTimeBy(recoveryInterval + 25L)
                assertEquals(2, store.recoverCallCount.get())
            } finally {
                outboxDispatcher.closeSuspend()
                Files.deleteIfExists(Path.of(configPath))
            }

            assertEquals(listOf(60_000L, 60_000L), store.recoverOlderThanMs.take(2))
        }

    @Test
    fun `dispatcher recovers expired claims immediately on start`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val configPath = Files.createTempFile("iris-outbox-dispatcher", ".json").toFile().absolutePath
            val config = ConfigManager(configPath = configPath)
            val store = RecordingWebhookDeliveryStore(claimedEntries = emptyList())
            val outboxDispatcher =
                WebhookOutboxDispatcher(
                    config = config,
                    store = store,
                    deliveryPolicy =
                        WebhookDeliveryPolicy(
                            pollIntervalMs = 1_000L,
                            maxClaimBatchSize = 1,
                            claimRecoveryIntervalMs = 30_000L,
                        ),
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            try {
                outboxDispatcher.start()
                assertEquals(1, store.recoverCallCount.get())
                runCurrent()
                assertEquals(listOf(60_000L), store.recoverOlderThanMs.toList())
            } finally {
                outboxDispatcher.closeSuspend()
                Files.deleteIfExists(Path.of(configPath))
            }
        }

    @Test
    fun `close releases outstanding claims immediately without incrementing attempt`() {
        val requestStarted = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val port = reservePort()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris") { exchange ->
                    requestStarted.countDown()
                    CountDownLatch(1).await(300, TimeUnit.MILLISECONDS)
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }
                executor = Executors.newSingleThreadExecutor()
                start()
            }
        val configPath = Files.createTempFile("iris-outbox-dispatcher", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:$port/webhook/iris")
        val store =
            RecordingWebhookDeliveryStore(
                claimedEntries = listOf(claimedEntry(id = 1L, roomId = 1L, messageId = "msg-1")),
                onRelease = { releaseLatch.countDown() },
            )
        val outboxDispatcher =
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                transportOverride = "http1",
                deliveryPolicy =
                    WebhookDeliveryPolicy(
                        pollIntervalMs = 25L,
                        maxClaimBatchSize = 1,
                    ),
                clock = fakeClock::get,
            )

        try {
            outboxDispatcher.start()
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS), "dispatcher should start processing claimed entry")
            runBlocking { outboxDispatcher.closeSuspend() }
            assertTrue(releaseLatch.await(5, TimeUnit.SECONDS), "dispatcher should release claim during shutdown")
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)
                ?.shutdownNow()
            Files.deleteIfExists(Path.of(configPath))
        }

        assertEquals(listOf(1L), store.releasedIds.toList())
        assertTrue(store.releaseReasons.contains("dispatcher shutdown"))
        assertTrue(store.retriedIds.isEmpty(), "graceful close should not increment retry attempt")
    }

    @Test
    fun `dispatcher uses delivery policy max attempts instead of legacy dispatcher constant`() {
        val configPath = Files.createTempFile("iris-outbox-policy", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        val port = reservePort()
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:$port/webhook/iris")
        val deadLatch = CountDownLatch(1)
        val store =
            RecordingWebhookDeliveryStore(
                claimedEntries = listOf(claimedEntry(id = 1L, roomId = 1L, messageId = "msg-policy")),
                onDead = { deadLatch.countDown() },
            )
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris") { exchange ->
                    exchange.sendResponseHeaders(503, -1)
                    exchange.close()
                }
                executor = Executors.newSingleThreadExecutor()
                start()
            }

        try {
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                transportOverride = "http1",
                deliveryPolicy = WebhookDeliveryPolicy(maxDeliveryAttempts = 1),
                clock = fakeClock::get,
            ).use { dispatcher ->
                dispatcher.start()
                assertTrue(deadLatch.await(5, TimeUnit.SECONDS))
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)
                ?.shutdownNow()
            Files.deleteIfExists(Path.of(configPath))
        }

        assertEquals(listOf(1L), store.deadIds.toList())
        assertTrue(store.retriedIds.isEmpty())
        assertTrue(store.deadReasons.any { it.contains("status=503") })
    }

    private fun claimedEntry(
        id: Long,
        roomId: Long,
        messageId: String,
    ): ClaimedDelivery =
        ClaimedDelivery(
            id = id,
            roomId = roomId,
            route = DEFAULT_WEBHOOK_ROUTE,
            messageId = messageId,
            payloadJson = """{"messageId":"$messageId"}""",
            attemptCount = 0,
            claimToken = "test-token",
        )

    private fun reservePort(): Int =
        ServerSocket(0).use { socket ->
            socket.localPort
        }
}

// ============================
// 테스트 fake (동일 파일 내 private class)
// ============================
private class RecordingWebhookDeliveryStore(
    private val claimedEntries: List<ClaimedDelivery>,
    private val onRetry: () -> Unit = {},
    private val onRelease: () -> Unit = {},
    private val onDead: () -> Unit = {},
) : WebhookDeliveryStore {
    private var claimed = false
    val retriedIds = CopyOnWriteArrayList<Long>()
    val retryReasons = CopyOnWriteArrayList<String>()
    val releasedIds = CopyOnWriteArrayList<Long>()
    val releaseReasons = CopyOnWriteArrayList<String>()
    val deadIds = CopyOnWriteArrayList<Long>()
    val deadReasons = CopyOnWriteArrayList<String>()
    val recoverOlderThanMs = CopyOnWriteArrayList<Long>()
    val recoverCallCount = AtomicInteger(0)

    override fun enqueue(delivery: PendingWebhookDelivery): Long = 0L

    override fun claimReady(limit: Int): List<ClaimedDelivery> {
        if (claimed) return emptyList()
        claimed = true
        return claimedEntries.take(limit)
    }

    override fun markSent(
        id: Long,
        claimToken: String,
    ) {}

    override fun markRetry(
        id: Long,
        claimToken: String,
        nextAttemptAt: Long,
        reason: String?,
    ) {
        retriedIds += id
        retryReasons += reason.orEmpty()
        onRetry()
    }

    override fun markDead(
        id: Long,
        claimToken: String,
        reason: String?,
    ) {
        deadIds += id
        deadReasons += reason.orEmpty()
        onDead()
    }

    override fun releaseClaim(
        id: Long,
        claimToken: String,
        nextAttemptAt: Long,
        reason: String?,
    ) {
        releasedIds += id
        releaseReasons += reason.orEmpty()
        onRelease()
    }

    override fun recoverExpiredClaims(olderThanMs: Long): Int {
        recoverOlderThanMs += olderThanMs
        recoverCallCount.incrementAndGet()
        return 0
    }

    override fun close() {}
}
```

---

## 7. 레거시 참고: FileWebhookOutboxStore

> 현재 프로덕션 경로에서 사용되지 않음. 인터페이스가 다르며(`WebhookOutboxStore`), claimToken이 없고, 동시성을 `@Synchronized`로 처리함.
> 현행 SQLite 기반 설계의 origin을 보여주는 참고 자료로 포함.

```kotlin
// delivery/webhook/WebhookOutbox.kt

internal interface WebhookOutboxStore : Closeable {
    fun enqueue(entry: PendingWebhookOutboxEntry): Boolean
    fun claimReady(nowEpochMs: Long, limit: Int): List<StoredWebhookOutboxEntry>
    fun markSent(id: Long)
    fun markRetry(id: Long, nextAttemptAt: Long, lastError: String?)
    fun requeueClaim(id: Long, nextAttemptAt: Long, lastError: String?)
    fun markDead(id: Long, lastError: String?)
    fun recoverInFlight(nowEpochMs: Long)
}

internal class FileWebhookOutboxStore(
    private val file: File = File("${PathUtils.getAppPath()}databases/iris-outbox.json"),
    private val clock: () -> Long = System::currentTimeMillis,
) : WebhookOutboxStore {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun enqueue(entry: PendingWebhookOutboxEntry): Boolean {
        val state = readState()
        if (state.entries.any { it.messageId == entry.messageId }) return true
        val now = clock()
        val stored = StoredWebhookOutboxEntry(
            id = state.nextId,
            roomId = entry.roomId,
            route = entry.route,
            messageId = entry.messageId,
            payloadJson = entry.payloadJson,
            attemptCount = 0,
            nextAttemptAt = 0L,
            status = WebhookOutboxStatus.PENDING,
            createdAt = now,
            updatedAt = now,
        )
        writeState(state.copy(nextId = state.nextId + 1, entries = state.entries + stored))
        return true
    }

    @Synchronized
    override fun markSent(id: Long) {
        updateEntry(id) { entry ->
            entry.copy(status = WebhookOutboxStatus.SENT, updatedAt = clock(), lastError = null)
        }
    }

    @Synchronized
    override fun markRetry(id: Long, nextAttemptAt: Long, lastError: String?) {
        updateEntry(id) { entry ->
            entry.copy(
                status = WebhookOutboxStatus.RETRY,
                attemptCount = entry.attemptCount + 1,  // +1
                nextAttemptAt = nextAttemptAt,
                lastError = lastError,
                updatedAt = clock(),
            )
        }
    }

    @Synchronized
    override fun requeueClaim(id: Long, nextAttemptAt: Long, lastError: String?) {
        updateEntry(id) { entry ->
            entry.copy(
                status = WebhookOutboxStatus.RETRY,
                // attemptCount 변경 없음
                nextAttemptAt = nextAttemptAt,
                lastError = lastError,
                updatedAt = clock(),
            )
        }
    }

    @Synchronized
    override fun markDead(id: Long, lastError: String?) {
        updateEntry(id) { entry ->
            entry.copy(
                status = WebhookOutboxStatus.DEAD,
                attemptCount = entry.attemptCount + 1,  // +1
                lastError = lastError,
                updatedAt = clock(),
            )
        }
    }

    // ... (파일 I/O 생략)
}
```

```kotlin
// model/WebhookOutboxModels.kt

@Serializable
internal enum class WebhookOutboxStatus {
    PENDING,
    SENDING,   // 레거시: CLAIMED에 해당
    RETRY,
    SENT,
    DEAD,
}

@Serializable
internal data class StoredWebhookOutboxEntry(
    val id: Long,
    val roomId: Long,
    val route: String,
    val messageId: String,
    val payloadJson: String,
    val attemptCount: Int,
    val nextAttemptAt: Long,
    val status: WebhookOutboxStatus,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
```

---

## 8. 선택지 비교표

### Option A: 현행 유지 + `markDead`에 `incrementAttempt` 추가

**인터페이스 변경:**

```kotlin
interface WebhookDeliveryStore : Closeable {
    // ... 기존 5개 메서드 유지
    fun markDead(
        id: Long,
        claimToken: String,
        reason: String?,
        incrementAttempt: Boolean = true,  // 추가
    )
}
```

**SQL 변경 (SqliteWebhookDeliveryStore):**

```kotlin
override fun markDead(
    id: Long,
    claimToken: String,
    reason: String?,
    incrementAttempt: Boolean,
) {
    val attemptExpr = if (incrementAttempt) "attempt_count = attempt_count + 1," else ""
    db.update(
        """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
           SET status = 'DEAD', $attemptExpr
               last_error = ?, claim_token = NULL, claimed_at = NULL, updated_at = ?
           WHERE id = ? AND claim_token = ?""",
        listOf(reason, clock(), id, claimToken),
    )
}
```

**호출부 변경 (WebhookOutboxDispatcher.processEntry):**

```kotlin
// L119: URL 미설정 — 기존 markDead에 incrementAttempt = false 추가
store.markDead(entry.id, entry.claimToken, "no webhook URL configured for route=${entry.route}", incrementAttempt = false)
```

다른 호출부(L154, L185)는 기본값 `true`가 적용되므로 변경 없음.

| 항목 | 평가 |
|---|---|
| 변경 범위 | 최소: 인터페이스 1줄, 구현 SQL 1줄, 호출처 1곳, fake 1줄 |
| attempt_count 문제 | 해결 |
| 구조적 문제 (retry/dead 분산) | **미해결** |
| markRetry/releaseClaim 혼동 위험 | **미해결** |
| 기존 테스트 영향 | markDead 호출하는 테스트 1개 (기본값으로 호환) |

---

### Option B: `markRetry` + `markDead` → `resolveFailure` 통합 (추천)

**새 타입:**

```kotlin
// persistence/WebhookDeliveryStore.kt 에 추가

sealed class FailureOutcome {
    data class Retry(
        val nextAttemptAt: Long,
        val reason: String?,
    ) : FailureOutcome()

    data class Dead(
        val reason: String?,
        val countAsAttempt: Boolean = true,
    ) : FailureOutcome()
}
```

**인터페이스 변경:**

```kotlin
interface WebhookDeliveryStore : Closeable {
    fun enqueue(delivery: PendingWebhookDelivery): Long
    fun claimReady(limit: Int): List<ClaimedDelivery>

    fun markSent(id: Long, claimToken: String)                                    // 유지

    fun resolveFailure(id: Long, claimToken: String, outcome: FailureOutcome)     // 신규 (markRetry + markDead 통합)

    fun releaseClaim(id: Long, claimToken: String, nextAttemptAt: Long, reason: String?)  // 유지

    fun recoverExpiredClaims(olderThanMs: Long): Int
}
```

**구현 변경 (SqliteWebhookDeliveryStore):**

```kotlin
// markRetry, markDead 삭제 → resolveFailure로 대체

override fun resolveFailure(
    id: Long,
    claimToken: String,
    outcome: FailureOutcome,
) {
    when (outcome) {
        is FailureOutcome.Retry -> {
            db.update(
                """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                   SET status = 'RETRY', attempt_count = attempt_count + 1,
                       next_attempt_at = ?, last_error = ?,
                       claim_token = NULL, claimed_at = NULL, updated_at = ?
                   WHERE id = ? AND claim_token = ?""",
                listOf(outcome.nextAttemptAt, outcome.reason, clock(), id, claimToken),
            )
        }
        is FailureOutcome.Dead -> {
            val attemptExpr = if (outcome.countAsAttempt) "attempt_count = attempt_count + 1," else ""
            db.update(
                """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                   SET status = 'DEAD', $attemptExpr
                       last_error = ?,
                       claim_token = NULL, claimed_at = NULL, updated_at = ?
                   WHERE id = ? AND claim_token = ?""",
                listOf(outcome.reason, clock(), id, claimToken),
            )
        }
    }
}
```

**디스패처 변경 (WebhookOutboxDispatcher.processEntry):**

```kotlin
private suspend fun processEntry(entry: ClaimedDelivery) {
    try {
        val url = config.webhookEndpointFor(entry.route).takeIf { it.isNotBlank() }
        if (url.isNullOrBlank()) {
            store.resolveFailure(
                entry.id, entry.claimToken,
                FailureOutcome.Dead("no webhook URL configured for route=${entry.route}", countAsAttempt = false),
            )
            return
        }

        val request = requestFactory.create(
            WebhookDelivery(
                url = url,
                messageId = entry.messageId,
                route = entry.route,
                payloadJson = entry.payloadJson,
                attempt = entry.attemptCount,
            ),
        )

        val statusCode =
            try {
                deliveryClient.execute(request, url)
            } catch (error: Exception) {
                if (shuttingDown) {
                    releaseClaimIfOutstanding(entry = entry, nextAttemptAt = clock(), reason = "dispatcher shutdown")
                    return
                }
                scheduleRetryOrDead(entry, error.message)
                return
            }

        if (shuttingDown) {
            releaseClaimIfOutstanding(entry = entry, nextAttemptAt = clock(), reason = "dispatcher shutdown")
            return
        }

        when {
            statusCode in 200..299 -> store.markSent(entry.id, entry.claimToken)
            shouldRetryStatus(statusCode) -> scheduleRetryOrDead(entry, "status=$statusCode")
            else -> store.resolveFailure(
                entry.id, entry.claimToken,
                FailureOutcome.Dead("status=$statusCode"),
            )
        }
    } catch (cancelled: CancellationException) {
        releaseClaimIfOutstanding(entry = entry, nextAttemptAt = clock(), reason = "dispatcher shutdown")
        throw cancelled
    } finally {
        outstandingClaims.remove(entry.id, entry)
    }
}
```

**디스패처 변경 (WebhookOutboxDispatcher.scheduleRetryOrDead):**

```kotlin
private fun scheduleRetryOrDead(
    entry: ClaimedDelivery,
    reason: String?,
) {
    val outcome = when (
        val retrySchedule = nextDeliveryRetrySchedule(
            attempt = entry.attemptCount,
            maxDeliveryAttempts = deliveryPolicy.maxDeliveryAttempts,
            backoffDelayProvider = backoffDelayProvider,
        )
    ) {
        is DeliveryRetrySchedule.RetryAttempt ->
            FailureOutcome.Retry(
                nextAttemptAt = clock() + retrySchedule.delayMs,
                reason = reason,
            )
        is DeliveryRetrySchedule.Exhausted ->
            FailureOutcome.Dead(
                reason = reason ?: "delivery attempts exhausted",
            )
    }
    store.resolveFailure(entry.id, entry.claimToken, outcome)
}
```

**테스트 fake 변경 (RecordingWebhookDeliveryStore):**

```kotlin
private class RecordingWebhookDeliveryStore(
    private val claimedEntries: List<ClaimedDelivery>,
    private val onRetry: () -> Unit = {},
    private val onRelease: () -> Unit = {},
    private val onDead: () -> Unit = {},
) : WebhookDeliveryStore {
    private var claimed = false
    val retriedIds = CopyOnWriteArrayList<Long>()
    val retryReasons = CopyOnWriteArrayList<String>()
    val releasedIds = CopyOnWriteArrayList<Long>()
    val releaseReasons = CopyOnWriteArrayList<String>()
    val deadIds = CopyOnWriteArrayList<Long>()
    val deadReasons = CopyOnWriteArrayList<String>()
    val recoverOlderThanMs = CopyOnWriteArrayList<Long>()
    val recoverCallCount = AtomicInteger(0)

    override fun enqueue(delivery: PendingWebhookDelivery): Long = 0L

    override fun claimReady(limit: Int): List<ClaimedDelivery> {
        if (claimed) return emptyList()
        claimed = true
        return claimedEntries.take(limit)
    }

    override fun markSent(id: Long, claimToken: String) {}

    // markRetry + markDead 삭제 → resolveFailure로 대체
    override fun resolveFailure(id: Long, claimToken: String, outcome: FailureOutcome) {
        when (outcome) {
            is FailureOutcome.Retry -> {
                retriedIds += id
                retryReasons += outcome.reason.orEmpty()
                onRetry()
            }
            is FailureOutcome.Dead -> {
                deadIds += id
                deadReasons += outcome.reason.orEmpty()
                onDead()
            }
        }
    }

    override fun releaseClaim(id: Long, claimToken: String, nextAttemptAt: Long, reason: String?) {
        releasedIds += id
        releaseReasons += reason.orEmpty()
        onRelease()
    }

    override fun recoverExpiredClaims(olderThanMs: Long): Int {
        recoverOlderThanMs += olderThanMs
        recoverCallCount.incrementAndGet()
        return 0
    }

    override fun close() {}
}
```

**SqliteWebhookDeliveryStoreTest 변경 요점:**

```kotlin
// 기존 markRetry 테스트: 호출을 resolveFailure(FailureOutcome.Retry(...))로 교체
store.resolveFailure(
    id = firstClaim.id,
    claimToken = firstClaim.claimToken,
    outcome = FailureOutcome.Retry(nextAttemptAt = 6000L, reason = "temporary failure"),
)

// 기존 markDead 테스트: 호출을 resolveFailure(FailureOutcome.Dead(...))로 교체
store.resolveFailure(claim.id, claim.claimToken, FailureOutcome.Dead("permanent failure"))

// 기존 stale markRetry 테스트: 동일 패턴
store.resolveFailure(
    id = firstClaim.id,
    claimToken = firstClaim.claimToken,
    outcome = FailureOutcome.Retry(nextAttemptAt = 60000L, reason = "stale retry"),
)
```

| 항목 | 평가 |
|---|---|
| 변경 범위 | 중간: 인터페이스, 구현체, 디스패처, fake, 테스트 3개 파일 |
| attempt_count 문제 | 해결 (`countAsAttempt = false`) |
| 구조적 문제 (retry/dead 분산) | **해결** — sealed class가 outcome을 타입으로 강제 |
| markRetry/releaseClaim 혼동 위험 | **해결** — `markRetry` 삭제됨, `releaseClaim`만 남음 |
| retry/dead 표현 통합 | O — failure outcome이 타입으로 중앙화됨 |
| max attempts 정책 소유권 이동 | X — 현재 구현은 여전히 dispatcher가 exhaustion policy를 결정함 |
| 기존 테스트 영향 | 시그니처 교체 필요하나 의미 변경 없음 |

---

### Option C: 전부 `resolveAttempt` 단일 진입점

**새 타입:**

```kotlin
sealed class AttemptOutcome {
    object Delivered : AttemptOutcome()
    data class Retry(val nextAttemptAt: Long, val reason: String?) : AttemptOutcome()
    data class Dead(val reason: String?, val countAsAttempt: Boolean = true) : AttemptOutcome()
    data class Released(val nextAttemptAt: Long, val reason: String?) : AttemptOutcome()
}
```

**인터페이스 변경:**

```kotlin
interface WebhookDeliveryStore : Closeable {
    fun enqueue(delivery: PendingWebhookDelivery): Long
    fun claimReady(limit: Int): List<ClaimedDelivery>
    fun resolveAttempt(id: Long, claimToken: String, outcome: AttemptOutcome)    // 4개 통합
    fun recoverExpiredClaims(olderThanMs: Long): Int
}
```

| 항목 | 평가 |
|---|---|
| 변경 범위 | Option B 동일 + `releaseClaim` 호출처 5곳 추가 변경 |
| attempt_count 문제 | 해결 |
| 구조적 문제 | 해결 |
| 과잉 설계 위험 | **있음** — `markSent`(파라미터 없음)와 `releaseClaim`(셧다운 전용)을 실패 outcome과 같은 범주에 넣는 것은 의미적으로 부자연스러움 |
| `Delivered`와 `Released`의 차이 | 전자는 성공, 후자는 "시도 안 함" — 같은 sealed hierarchy에 있으면 when 분기에서 항상 4개를 처리해야 함 |

---

## 9. 블라스트 레이디어스 (Option B 기준)

| 파일 | 변경 내용 |
|---|---|
| `persistence/WebhookDeliveryStore.kt` | `markRetry`, `markDead` 삭제 → `FailureOutcome` sealed class + `resolveFailure` 추가 |
| `persistence/SqliteWebhookDeliveryStore.kt` | `markRetry`, `markDead` 구현 삭제 → `resolveFailure` when 분기 구현 |
| `delivery/webhook/WebhookOutboxDispatcher.kt` | `processEntry`와 `scheduleRetryOrDead`에서 `store.markRetry`/`store.markDead` → `store.resolveFailure` |
| `WebhookOutboxDispatcherTest.kt` 내 `RecordingWebhookDeliveryStore` | `markRetry`, `markDead` 삭제 → `resolveFailure` 구현 |
| `SqliteWebhookDeliveryStoreTest.kt` | `store.markRetry(...)` → `store.resolveFailure(FailureOutcome.Retry(...))`, `store.markDead(...)` → `store.resolveFailure(FailureOutcome.Dead(...))` |
| `delivery/webhook/RoutingGateway.kt` | 변경 없음 (`enqueue`만 사용) |
| `persistence/IrisDatabaseSchema.kt` | 변경 없음 |
| `delivery/webhook/WebhookOutbox.kt` (레거시) | 변경 없음 (별도 인터페이스) |

영향 범위 외 파일: `PersistenceFactory.kt`, `AppRuntime.kt`, `SnapshotRuntimeFactory.kt`, `AppRuntimeWiringTest.kt`, `SqliteDurabilityTest.kt` — 이들은 `enqueue`/`claimReady`/`recoverExpiredClaims`/`close`만 사용하므로 영향 없음.

---

## 10. 결정: Option B' 채택 (2026-04-01)

A/B/C 검토 후 **Option B를 기반으로 한 정제안 B'**를 채택·구현함.

### B'가 원안 B와 다른 점

| 항목 | 원안 B | B' (채택) |
|---|---|---|
| Dead의 attempt 제어 | `countAsAttempt: Boolean` 파라미터 | `PermanentFailure` / `RejectedBeforeAttempt` 타입 분리 |
| 전이 메서드 반환값 | `Unit` | `ClaimTransitionResult` (APPLIED / STALE_CLAIM) |
| `releaseClaim` 이름 | 유지 | `requeueClaim`으로 변경 |
| `markSent`의 `last_error` | 건드리지 않음 | `NULL`로 정리 |
| WHERE 조건 | `claim_token = ?` 만 | `claim_token = ? AND status = 'CLAIMED'` |
| stale token 테스트 | 간접 검증 (claimReady 결과) | `ClaimTransitionResult.STALE_CLAIM` + DB row 직접 검증 |

### 채택 근거

1. **boolean flag 제거** — `countAsAttempt: Boolean`은 타입 안전성 도입 취지에 역행. `PermanentFailure`와 `RejectedBeforeAttempt`를 별도 타입으로 분리하면 호출부가 의미를 명시적으로 선택함.
2. **`ClaimTransitionResult` 반환** — stale claim 감지를 호출자가 직접 확인 가능. 운영 로그/메트릭 연동 근거.
3. **`requeueClaim` 명명** — 레거시 인터페이스(`WebhookOutboxStore`)도 `requeueClaim`이었으므로 팀 인지 비용 최소.
4. **`markSent`의 `last_error = NULL`** — 성공 후에도 과거 에러가 남으면 운영 오해 유발.
5. **`AND status = 'CLAIMED'`** — 상태기계 불변식을 SQL 수준에서 강제.

> **주의**: B'는 failure 표현을 중앙화한 것이지, retry exhaustion policy를 store로 이동한 것은 아님.
> 현재 `maxDeliveryAttempts` 결정은 `WebhookOutboxDispatcher.scheduleRetryOrDead()`가 소유한다.

### 구현된 최종 인터페이스

```kotlin
sealed interface FailureOutcome {
    data class Retry(val nextAttemptAt: Long, val reason: String?) : FailureOutcome
    data class PermanentFailure(val reason: String?) : FailureOutcome
    data class RejectedBeforeAttempt(val reason: String?) : FailureOutcome
}

enum class ClaimTransitionResult { APPLIED, STALE_CLAIM }

interface WebhookDeliveryStore : Closeable {
    fun enqueue(delivery: PendingWebhookDelivery): Long
    fun claimReady(limit: Int): List<ClaimedDelivery>
    fun markSent(id: Long, claimToken: String): ClaimTransitionResult
    fun resolveFailure(id: Long, claimToken: String, outcome: FailureOutcome): ClaimTransitionResult
    fun requeueClaim(id: Long, claimToken: String, nextAttemptAt: Long, reason: String?): ClaimTransitionResult
    fun recoverExpiredClaims(olderThanMs: Long): Int
}
```

### 변경된 파일

| 파일 | 변경 내용 |
|---|---|
| `persistence/WebhookDeliveryStore.kt` | `FailureOutcome` sealed interface (3-way), `ClaimTransitionResult` enum, `resolveFailure` + `requeueClaim` 신규, `attemptCount` → `failedAttemptCount` rename |
| `persistence/SqliteWebhookDeliveryStore.kt` | `resolveFailure` when 분기, `requeueClaim`, `markSent`에 `last_error = NULL`, 전체 `AND status = 'CLAIMED'`, `transitionResult` 헬퍼, `claimReady` row별 UUID 발행 |
| `delivery/webhook/ClaimTransitionObserver.kt` | `ClaimTransitionObserver` fun interface 신규 — stale claim 소비 경로를 테스트 가능하게 분리 |
| `delivery/webhook/WebhookOutboxDispatcher.kt` | `processEntry` `attemptStarted` 분기로 post-attempt requeueClaim 의미 버그 수정, `closeSuspend` 순서 변경 (job cancel → release), `ClaimTransitionObserver` 주입 |
| `WebhookOutboxDispatcherTest.kt` | `RecordingWebhookDeliveryStore` configurable 반환값 + outcome-type 4개 + stale observer 1개 + post-attempt 의미 보장 3개 |
| `SqliteWebhookDeliveryStoreTest.kt` | 기존 마이그레이션 + 신규 5개 (RejectedBeforeAttempt, stale STALE_CLAIM, markSent clears last_error, requeueClaim stale, distinct claimToken) |

---

## 11. 최종 구현 코드 (전체)

### 11.1. 인터페이스 + sealed types

```kotlin
// persistence/WebhookDeliveryStore.kt

package party.qwer.iris.persistence

import java.io.Closeable

data class PendingWebhookDelivery(
    val messageId: String,
    val roomId: Long,
    val route: String,
    val payloadJson: String,
)

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

/**
 * CLAIMED 상태에서의 전이 결과.
 * [APPLIED]이면 전이 성공, [STALE_CLAIM]이면 claim_token 불일치 또는 이미 전이됨.
 */
enum class ClaimTransitionResult {
    APPLIED,
    STALE_CLAIM,
}

interface WebhookDeliveryStore : Closeable {
    fun enqueue(delivery: PendingWebhookDelivery): Long

    fun claimReady(limit: Int): List<ClaimedDelivery>

    /** 전달 성공. CLAIMED -> SENT. last_error를 정리함. */
    fun markSent(
        id: Long,
        claimToken: String,
    ): ClaimTransitionResult

    /**
     * 전달 실패 후 처리. outcome 타입에 따라 CLAIMED -> RETRY 또는 CLAIMED -> DEAD.
     * failedAttemptCount 증가 여부는 outcome 타입이 결정함.
     */
    fun resolveFailure(
        id: Long,
        claimToken: String,
        outcome: FailureOutcome,
    ): ClaimTransitionResult

    /**
     * 시도로 카운트하지 않는 CLAIMED -> RETRY 복귀.
     * 셧다운, 큐 포화 등 시스템 사유로 처리를 포기할 때 사용. failedAttemptCount 변경 없음.
     * requeueClaim은 HTTP 시도 전에만 사용해야 함.
     */
    fun requeueClaim(
        id: Long,
        claimToken: String,
        nextAttemptAt: Long,
        reason: String?,
    ): ClaimTransitionResult

    fun recoverExpiredClaims(olderThanMs: Long): Int
}
```

### 11.2. SQLite 구현체

```kotlin
// persistence/SqliteWebhookDeliveryStore.kt

class SqliteWebhookDeliveryStore(
    private val db: SqliteDriver,
    private val clock: () -> Long = System::currentTimeMillis,
) : WebhookDeliveryStore {
    override fun enqueue(delivery: PendingWebhookDelivery): Long =
        db.inImmediateTransaction {
            val now = clock()
            update(
                """INSERT OR IGNORE INTO ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                   (message_id, room_id, route, payload_json, status, attempt_count, next_attempt_at, created_at, updated_at)
                   VALUES (?, ?, ?, ?, 'PENDING', 0, 0, ?, ?)""",
                listOf(delivery.messageId, delivery.roomId, delivery.route, delivery.payloadJson, now, now),
            )
            queryLong(
                "SELECT id FROM ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE} WHERE message_id = ?",
                delivery.messageId,
            )!!
        }

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

    override fun recoverExpiredClaims(olderThanMs: Long): Int {
        val cutoff = clock() - olderThanMs
        return db.update(
            """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
               SET status = 'RETRY', claim_token = NULL, claimed_at = NULL, updated_at = ?
               WHERE status = 'CLAIMED' AND claimed_at <= ?""",
            listOf(clock(), cutoff),
        )
    }

    override fun close() {}

    private fun transitionResult(affectedRows: Int): ClaimTransitionResult =
        if (affectedRows > 0) ClaimTransitionResult.APPLIED else ClaimTransitionResult.STALE_CLAIM
}
```

### 11.3. ClaimTransitionObserver (신규)

```kotlin
// delivery/webhook/ClaimTransitionObserver.kt

internal fun interface ClaimTransitionObserver {
    fun onResult(
        operation: String,
        entry: ClaimedDelivery,
        result: ClaimTransitionResult,
    )
}
```

### 11.4. Outbox 디스패처 (소비자)

```kotlin
// delivery/webhook/WebhookOutboxDispatcher.kt

internal class WebhookOutboxDispatcher(
    private val config: ConfigProvider,
    private val store: WebhookDeliveryStore,
    transportOverride: String? = null,
    private val partitionCount: Int = 4,
    private val deliveryPolicy: WebhookDeliveryPolicy = WebhookDeliveryPolicy(),
    private val claimTransitionObserver: ClaimTransitionObserver = ClaimTransitionObserver { operation, entry, result ->
        if (result == ClaimTransitionResult.STALE_CLAIM) {
            IrisLogger.warn(
                "[OutboxDispatcher] Stale claim ignored: operation=$operation, id=${entry.id}, messageId=${entry.messageId}",
            )
        }
    },
    private val backoffDelayProvider: (Int) -> Long = ::nextBackoffDelayMs,
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {
    /* ... 필드, start(), pumpReadyEntries() 등은 변경 없음 ... */

    private suspend fun processEntry(entry: ClaimedDelivery) {
        var attemptStarted = false
        try {
            val url = config.webhookEndpointFor(entry.route).takeIf { it.isNotBlank() }
            if (url.isNullOrBlank()) {
                observeResult(
                    "resolveFailure", entry,
                    store.resolveFailure(
                        entry.id, entry.claimToken,
                        FailureOutcome.RejectedBeforeAttempt("no webhook URL configured for route=${entry.route}"),
                    ),
                )
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
                requestFactory.create(
                    WebhookDelivery(
                        url = url,
                        messageId = entry.messageId,
                        route = entry.route,
                        payloadJson = entry.payloadJson,
                        attempt = entry.failedAttemptCount,
                    ),
                )

            attemptStarted = true
            val statusCode = deliveryClient.execute(request, url)

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
        } catch (error: Exception) {
            if (!attemptStarted) {
                releaseClaimIfOutstanding(
                    entry = entry,
                    nextAttemptAt = clock(),
                    reason = "unexpected error before delivery attempt: ${error.message}",
                )
            } else {
                scheduleRetryOrDead(entry, error.message)
            }
        } finally {
            outstandingClaims.remove(entry.id, entry)
        }
    }

    private fun scheduleRetryOrDead(
        entry: ClaimedDelivery,
        reason: String?,
    ) {
        val outcome =
            when (
                val retrySchedule =
                    nextDeliveryRetrySchedule(
                        attempt = entry.failedAttemptCount,
                        maxDeliveryAttempts = deliveryPolicy.maxDeliveryAttempts,
                        backoffDelayProvider = backoffDelayProvider,
                    )
            ) {
                is DeliveryRetrySchedule.RetryAttempt ->
                    FailureOutcome.Retry(
                        nextAttemptAt = clock() + retrySchedule.delayMs,
                        reason = reason,
                    )

                is DeliveryRetrySchedule.Exhausted ->
                    FailureOutcome.PermanentFailure(
                        reason = reason ?: "delivery attempts exhausted",
                    )
            }
        observeResult("resolveFailure", entry, store.resolveFailure(entry.id, entry.claimToken, outcome))
    }

    override fun close() {
        runBlocking { closeSuspend() }
    }

    suspend fun closeSuspend() {
        shuttingDown = true
        val job = pollingJob
        pollingJob = null
        job?.cancelAndJoin()
        routePartitions.values.forEach { route ->
            route.partitions.forEach { partition ->
                partition.channel.close()
            }
        }
        routePartitions.values.forEach { route ->
            route.partitions.forEach { partition ->
                partition.job.cancelAndJoin()
            }
        }
        releaseOutstandingClaims()
        scopeJob.cancelAndJoin()
        clientFactory
            .clientFor("http://127.0.0.1")
            .dispatcher.executorService
            .shutdownNow()
        clientFactory.clientFor("http://127.0.0.1").connectionPool.evictAll()
        store.close()
    }

    private fun releaseOutstandingClaims() {
        outstandingClaims.values.toList().forEach { entry ->
            releaseClaimIfOutstanding(entry = entry, nextAttemptAt = clock(), reason = "dispatcher shutdown")
        }
    }

    private fun releaseClaimIfOutstanding(
        entry: ClaimedDelivery,
        nextAttemptAt: Long,
        reason: String,
    ): Boolean {
        if (!outstandingClaims.remove(entry.id, entry)) {
            return false
        }
        observeResult(
            "requeueClaim", entry,
            store.requeueClaim(
                id = entry.id,
                claimToken = entry.claimToken,
                nextAttemptAt = nextAttemptAt,
                reason = reason,
            ),
        )
        return true
    }

    private fun observeResult(
        operation: String,
        entry: ClaimedDelivery,
        result: ClaimTransitionResult,
    ): ClaimTransitionResult {
        claimTransitionObserver.onResult(operation, entry, result)
        return result
    }
}
```

### 11.5. 테스트 fake

```kotlin
// delivery/webhook/WebhookOutboxDispatcherTest.kt 내 private class

private class RecordingWebhookDeliveryStore(
    private val claimedEntries: List<ClaimedDelivery>,
    private val markSentResult: ClaimTransitionResult = ClaimTransitionResult.APPLIED,
    private val resolveFailureResult: ClaimTransitionResult = ClaimTransitionResult.APPLIED,
    private val requeueResult: ClaimTransitionResult = ClaimTransitionResult.APPLIED,
    private val onMarkSent: () -> Unit = {},
    private val onRetry: () -> Unit = {},
    private val onRelease: () -> Unit = {},
    private val onDead: () -> Unit = {},
) : WebhookDeliveryStore {
    private var claimed = false
    val retriedIds = CopyOnWriteArrayList<Long>()
    val retryReasons = CopyOnWriteArrayList<String>()
    val releasedIds = CopyOnWriteArrayList<Long>()
    val releaseReasons = CopyOnWriteArrayList<String>()
    val deadIds = CopyOnWriteArrayList<Long>()
    val deadReasons = CopyOnWriteArrayList<String>()
    val resolvedOutcomes = CopyOnWriteArrayList<FailureOutcome>()
    val recoverOlderThanMs = CopyOnWriteArrayList<Long>()
    val recoverCallCount = AtomicInteger(0)

    override fun enqueue(delivery: PendingWebhookDelivery): Long = 0L

    override fun claimReady(limit: Int): List<ClaimedDelivery> {
        if (claimed) return emptyList()
        claimed = true
        return claimedEntries.take(limit)
    }

    override fun markSent(
        id: Long,
        claimToken: String,
    ): ClaimTransitionResult {
        onMarkSent()
        return markSentResult
    }

    override fun resolveFailure(
        id: Long,
        claimToken: String,
        outcome: FailureOutcome,
    ): ClaimTransitionResult {
        resolvedOutcomes += outcome
        when (outcome) {
            is FailureOutcome.Retry -> {
                retriedIds += id
                retryReasons += outcome.reason.orEmpty()
                onRetry()
            }
            is FailureOutcome.PermanentFailure -> {
                deadIds += id
                deadReasons += outcome.reason.orEmpty()
                onDead()
            }
            is FailureOutcome.RejectedBeforeAttempt -> {
                deadIds += id
                deadReasons += outcome.reason.orEmpty()
                onDead()
            }
        }
        return resolveFailureResult
    }

    override fun requeueClaim(
        id: Long,
        claimToken: String,
        nextAttemptAt: Long,
        reason: String?,
    ): ClaimTransitionResult {
        releasedIds += id
        releaseReasons += reason.orEmpty()
        onRelease()
        return requeueResult
    }

    override fun recoverExpiredClaims(olderThanMs: Long): Int {
        recoverOlderThanMs += olderThanMs
        recoverCallCount.incrementAndGet()
        return 0
    }

    override fun close() {}
}
```

### 11.6. 저장소 테스트 (신규/변경분만)

```kotlin
// persistence/SqliteWebhookDeliveryStoreTest.kt — 신규 4개 + 주요 변경 3개

// [신규] RejectedBeforeAttempt — attempt_count 미증가 + status=DEAD 직접 검증
@Test
fun `resolveFailure RejectedBeforeAttempt does not increment attempt count`() {
    val (helper, store) = createStore()
    helper.use { store.use {
        store.enqueue(PendingWebhookDelivery("message-1", 100L, "default", "{\"text\":\"hello\"}"))
        val claim = store.claimReady(limit = 1).single()

        assertEquals(
            ClaimTransitionResult.APPLIED,
            store.resolveFailure(claim.id, claim.claimToken, FailureOutcome.RejectedBeforeAttempt("no webhook URL")),
        )

        assertTrue(store.claimReady(limit = 10).isEmpty())
        assertEquals(0L, helper.queryLong("SELECT attempt_count FROM webhook_outbox WHERE id = ?", claim.id))
        assertEquals("DEAD", helper.query("SELECT status FROM webhook_outbox WHERE id = ?", listOf(claim.id)) { it.getString(0) }.single())
    }}
}

// [신규] stale claim — ClaimTransitionResult.STALE_CLAIM + DB row 직접 검증
@Test
fun `stale claimToken returns STALE_CLAIM and does not overwrite newer claim`() {
    var now = 1000L
    val (helper, store) = createStore(clock = { now })
    helper.use { store.use {
        store.enqueue(PendingWebhookDelivery("message-1", 100L, "default", "{\"text\":\"hello\"}"))

        val firstClaim = store.claimReady(limit = 1).single()
        now = 32000L
        assertEquals(1, store.recoverExpiredClaims(olderThanMs = 30000L))
        val secondClaim = store.claimReady(limit = 1).single()

        assertEquals(ClaimTransitionResult.STALE_CLAIM, store.markSent(firstClaim.id, firstClaim.claimToken))
        assertEquals("CLAIMED", helper.query("SELECT status FROM webhook_outbox WHERE id = ?", listOf(firstClaim.id)) { it.getString(0) }.single())

        assertEquals(ClaimTransitionResult.APPLIED, store.markSent(secondClaim.id, secondClaim.claimToken))
        assertTrue(store.claimReady(limit = 10).isEmpty())
    }}
}

// [신규] markSent가 last_error를 정리하는지 검증
@Test
fun `markSent clears last_error from previous retry`() {
    var now = 1000L
    val (helper, store) = createStore(clock = { now })
    helper.use { store.use {
        store.enqueue(PendingWebhookDelivery("message-1", 100L, "default", "{\"text\":\"hello\"}"))

        val firstClaim = store.claimReady(limit = 1).single()
        store.resolveFailure(firstClaim.id, firstClaim.claimToken, FailureOutcome.Retry(nextAttemptAt = 2000L, reason = "status=503"))
        assertEquals("status=503", helper.query("SELECT last_error FROM webhook_outbox WHERE id = ?", listOf(firstClaim.id)) { it.getString(0) }.single())

        now = 2000L
        val secondClaim = store.claimReady(limit = 1).single()
        store.markSent(secondClaim.id, secondClaim.claimToken)

        val lastError = helper.query("SELECT last_error FROM webhook_outbox WHERE id = ?", listOf(secondClaim.id)) { it.getStringOrNull(0) }.single()
        assertTrue(lastError == null, "last_error should be cleared after markSent, got: $lastError")
    }}
}

// [신규] requeueClaim stale claim — 대칭 커버리지
@Test
fun `requeueClaim with stale claimToken returns STALE_CLAIM`() {
    var now = 1000L
    val (helper, store) = createStore(clock = { now })
    helper.use { store.use {
        store.enqueue(PendingWebhookDelivery("message-1", 100L, "default", "{\"text\":\"hello\"}"))

        val firstClaim = store.claimReady(limit = 1).single()
        now = 32000L
        assertEquals(1, store.recoverExpiredClaims(olderThanMs = 30000L))
        val secondClaim = store.claimReady(limit = 1).single()

        assertEquals(ClaimTransitionResult.STALE_CLAIM, store.requeueClaim(firstClaim.id, firstClaim.claimToken, now, "stale requeue"))
        assertEquals("CLAIMED", helper.query("SELECT status FROM webhook_outbox WHERE id = ?", listOf(firstClaim.id)) { it.getString(0) }.single())

        assertEquals(ClaimTransitionResult.APPLIED, store.markSent(secondClaim.id, secondClaim.claimToken))
    }}
}

// [변경] markRetry → resolveFailure(Retry)
@Test
fun `resolveFailure Retry increments attempt and delays next claim`() { /* ... */ }

// [변경] releaseClaim → requeueClaim
@Test
fun `requeueClaim preserves attempt count and makes entry immediately claimable`() { /* ... */ }

// [변경] markDead → resolveFailure(PermanentFailure)
@Test
fun `resolveFailure PermanentFailure increments attempt count and prevents further claim`() { /* ... */ }

// [변경] stale markRetry → stale resolveFailure
@Test
fun `resolveFailure with stale claimToken returns STALE_CLAIM`() { /* ... */ }
```

---

## 12. 리뷰 이력

### Round 1 — 초기 리뷰

| 유형 | 지적 | 상태 |
|---|---|---|
| Warning | 디스패처가 `ClaimTransitionResult` 반환값을 소비하지 않아 stale claim이 묵살됨 | **R2에서 해소** |
| Warning | `RecordingWebhookDeliveryStore` fake가 `FailureOutcome` 타입을 기록하지 않음 | **R2에서 해소** |
| Improvement | `requeueClaim`의 stale-claim 테스트가 누락됨 | **R2에서 해소** |

### Round 2 — 1차 수정 후 재검증

| 유형 | 지적 | 상태 |
|---|---|---|
| Blocking | `warnIfStale`가 `IrisLogger.debug`를 사용 — 이름과 동작 불일치, 운영 환경에서 안 보임 | **R3에서 해소** |
| Warning | `resolvedOutcomes` 필드는 추가됐지만 디스패처 테스트에서 실제 assert 없음 | **R4에서 해소** |

### Round 3 — 재검증

| 유형 | 지적 | 상태 |
|---|---|---|
| Warning | `resolvedOutcomes` 타입별 assert 여전히 없음 (R2 deferred 재확인) | **R4에서 해소** |

### Round 4 — 외부 리뷰어 지적사항 반영 (종결 라운드)

리뷰어가 제기한 종결 조건 3가지를 전수 해소:

| 유형 | 지적 | 해소 방법 |
|---|---|---|
| Blocking | 디스패처 outcome-type assert 부재 — 핵심 의미 변화가 자동 검증으로 닫히지 않음 | 테스트 4개 추가: URL 미설정→`RejectedBeforeAttempt`, 400→`PermanentFailure`, 503→`Retry`, 503+exhausted→`PermanentFailure` |
| Blocking | stale `ClaimTransitionResult` 소비 경로에 회귀 테스트 없음 — `warnIfStale` 삭제 시 테스트 무감지 | `ClaimTransitionObserver` fun interface 분리 + `observeResult` 헬퍼 + configurable fake + observer 검증 테스트 1개 |
| Warning | 문서의 "store invariant enforcement" 설명이 실제 구현과 불일치 (retry policy ownership) | 비교표 수정, retry policy ownership 주의 문구 추가 |
| Improvement | `claimToken` batch granularity 문서 명확화 필요 | 후속 항목 및 섹션 14에 기록 |

**최종 판정: PASS WITH NOTES — R5에서 P0 의미 버그 추가 발견**

### Round 5 — 외부 리뷰어 2차 지적사항 반영

리뷰어가 제기한 P0/P1 수정사항:

| 유형 | 지적 | 해소 방법 |
|---|---|---|
| Blocking | `processEntry()`가 HTTP 시도 후에도 `requeueClaim()`으로 되돌릴 수 있는 의미 버그 — 중복 전송 및 잘못된 시도 카운팅 | `attemptStarted` 플래그 도입, 시도 후에는 반드시 `markSent`/`scheduleRetryOrDead`만 허용 |
| Blocking | `closeSuspend()`에서 `releaseOutstandingClaims()`가 partition job cancel 전에 실행 — in-flight entry도 requeue됨 | 순서 변경: partition job `cancelAndJoin` 먼저, 그 다음 잔여 `releaseOutstandingClaims` |
| Warning | `claimReady()`의 batch-level claimToken — row별 lease 추적 불가 | row별 UUID 발행으로 변경 |
| Improvement | `attemptCount` 이름이 실제 의미(누적 실패 횟수)와 불일치 | `failedAttemptCount`로 코드 레벨 rename (DB 컬럼 유지) |

**최종 판정: PASS — P0 의미 버그 수정 + P1 개선 전수 해소**

---

## 13. 후속 항목

| 항목 | 우선도 | 비고 |
|---|---|---|
| URL 미설정 시 `DEAD` vs `BLOCKED_CONFIG` 별도 상태 도입 | 제품 의존 | "설정 복구 후 자동 재전송" 요구 발생 시 |
| `STALE_CLAIM` 발생 빈도 메트릭 수집 | 낮음 | 현재 `ClaimTransitionObserver`로 관측 가능, 정량 메트릭은 후속 |
| claim lease heartbeat / timeout 정합성 | 낮음 | claim expiration이 delivery timeout보다 충분히 커야 함. 현재 at-least-once 보장. |
| `processEntry` 책임 분리 | 낮음 | executeAttempt / classifyResult / applyAction 3단 분리 검토 가능 |

---

## 14. 구현 비고

> `claimToken`은 row별 고유 UUID로 발행된다.
> `id + claimToken + status = 'CLAIMED'` 조합으로 상태 전이 정합성을 보장한다.
>
> `failedAttemptCount`는 DB 컬럼명 `attempt_count`와 다르다. 코드 레벨에서 의미를 명확히 하기 위한 rename이며, DB 컬럼 migration은 별도 작업으로 진행한다.
>
> webhook outbox는 at-least-once delivery를 보장한다. receiver는 `messageId` 기준 idempotent 처리가 필요하다.
