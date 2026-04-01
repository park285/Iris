package party.qwer.iris.delivery.webhook

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import party.qwer.iris.ConfigProvider
import party.qwer.iris.IrisLogger
import party.qwer.iris.persistence.ClaimTransitionResult
import party.qwer.iris.persistence.ClaimedDelivery
import party.qwer.iris.persistence.FailureOutcome
import party.qwer.iris.persistence.WebhookDeliveryStore
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

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

    private data class RoutePartitions(
        val route: String,
        val partitions: List<PartitionChannel>,
    )

    private data class PartitionChannel(
        val index: Int,
        val channel: Channel<ClaimedDelivery>,
        val job: Job,
    )

    private fun observeResult(
        operation: String,
        entry: ClaimedDelivery,
        result: ClaimTransitionResult,
    ): ClaimTransitionResult {
        claimTransitionObserver.onResult(operation, entry, result)
        return result
    }
}
