package party.qwer.iris.delivery.webhook

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import party.qwer.iris.ConfigManager
import party.qwer.iris.ConfigProvider
import party.qwer.iris.IrisLogger
import party.qwer.iris.http.RuntimeBootstrapState
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
    private val claimTransitionObserver: ClaimTransitionObserver =
        ClaimTransitionObserver { operation, entry, result ->
            if (result == ClaimTransitionResult.STALE_CLAIM) {
                IrisLogger.warn(
                    "[OutboxDispatcher] Stale claim ignored: operation=$operation, id=${entry.id}, messageId=${entry.messageId}",
                )
            }
        },
    private val backoffDelayProvider: (Int) -> Long = ::nextBackoffDelayMs,
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val createRequestOverride: ((WebhookDelivery) -> Request)? = null,
    private val executeRequestOverride: (suspend (Request, String) -> Int)? = null,
    clientFactoryOverride: WebhookHttpClientFactory? = null,
) : Closeable {
    init {
        require(partitionCount > 0) { "partitionCount must be > 0" }
    }

    private val scopeJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(scopeJob + dispatcher)
    private val clientFactory =
        clientFactoryOverride
            ?: WebhookHttpClientFactory(
                resolveWebhookTransport(transportOverride),
                okhttp3.Dispatcher(),
                okhttp3.ConnectionPool(),
            )
    private val requestFactory = WebhookRequestFactory(config)
    private val deliveryClient = WebhookDeliveryClient(clientFactory)
    private val requestBuilder: (WebhookDelivery) -> Request = createRequestOverride ?: requestFactory::create
    private val requestExecutor: suspend (Request, String) -> Int = executeRequestOverride ?: { request, url -> deliveryClient.execute(request, url) }
    private val routePartitions = ConcurrentHashMap<String, RoutePartitions>()
    private val outstandingClaims = ConcurrentHashMap<Long, ClaimedDelivery>()
    private val lifecycleLock = Any()

    @Volatile
    private var pollingJob: Job? = null

    @Volatile
    private var nextClaimRecoveryAtMs: Long = 0L

    @Volatile
    private var shuttingDown: Boolean = false

    fun start() {
        synchronized(lifecycleLock) {
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
                List(partitionCount) { index ->
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
        var heartbeatJob: Job? = null
        try {
            bootstrapFailureReason()?.let { reason ->
                releaseClaimIfOutstanding(
                    entry = entry,
                    nextAttemptAt = clock() + deliveryPolicy.pollIntervalMs,
                    reason = "dispatcher bootstrap not ready: $reason",
                )
                return
            }

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

    private fun bootstrapFailureReason(): String? =
        when (val runtimeConfig = config) {
            is ConfigManager ->
                when (val state = runtimeConfig.runtimeBootstrapState()) {
                    RuntimeBootstrapState.Ready -> null
                    is RuntimeBootstrapState.Blocked -> state.reason
                }
            else -> {
                val inboundConfigured = runtimeConfig.activeInboundSigningSecret().isNotBlank()
                val outboundConfigured = runtimeConfig.activeOutboundWebhookToken().isNotBlank()
                val botControlConfigured = runtimeConfig.activeBotControlToken().isNotBlank()
                when {
                    !inboundConfigured -> "inbound signing secret not configured"
                    !outboundConfigured -> "outbound webhook token not configured"
                    !botControlConfigured -> "bot control token not configured"
                    else -> null
                }
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
        val job =
            synchronized(lifecycleLock) {
                shuttingDown = true
                pollingJob.also { pollingJob = null }
            }
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
        clientFactory.shutdownSharedResources()
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
            "requeueClaim",
            entry,
            store.requeueClaim(
                id = entry.id,
                claimToken = entry.claimToken,
                nextAttemptAt = nextAttemptAt,
                reason = reason,
            ),
        )
        return true
    }

    private fun resolveRejectedBeforeAttempt(
        entry: ClaimedDelivery,
        reason: String,
    ): ClaimTransitionResult =
        observeResult(
            "resolveFailure",
            entry,
            store.resolveFailure(
                entry.id,
                entry.claimToken,
                FailureOutcome.RejectedBeforeAttempt(reason),
            ),
        )

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
