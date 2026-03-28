package party.qwer.iris.delivery.webhook

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
import party.qwer.iris.delivery.webhook.DeliveryRetrySchedule
import party.qwer.iris.delivery.webhook.WebhookDelivery
import party.qwer.iris.delivery.webhook.WebhookDeliveryClient
import party.qwer.iris.delivery.webhook.WebhookHttpClientFactory
import party.qwer.iris.delivery.webhook.WebhookRequestFactory
import party.qwer.iris.delivery.webhook.nextBackoffDelayMs
import party.qwer.iris.delivery.webhook.nextDeliveryRetrySchedule
import party.qwer.iris.delivery.webhook.resolveWebhookTransport
import party.qwer.iris.delivery.webhook.shouldRetryStatus
import party.qwer.iris.model.StoredWebhookOutboxEntry
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

internal class WebhookOutboxDispatcher(
    private val config: ConfigProvider,
    private val store: WebhookOutboxStore,
    transportOverride: String? = null,
    private val partitionCount: Int = 4,
    private val pollIntervalMs: Long = 200L,
    private val maxClaimBatchSize: Int = 64,
    private val partitionQueueCapacity: Int = 64,
    private val maxDeliveryAttempts: Int = H2cDispatcher.MAX_DELIVERY_ATTEMPTS,
    private val backoffDelayProvider: (Int) -> Long = ::nextBackoffDelayMs,
) : Closeable {
    private val scopeJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val clientFactory = WebhookHttpClientFactory(resolveWebhookTransport(transportOverride), okhttp3.Dispatcher(), okhttp3.ConnectionPool())
    private val requestFactory = WebhookRequestFactory(config)
    private val deliveryClient = WebhookDeliveryClient(clientFactory)
    private val routePartitions = ConcurrentHashMap<String, RoutePartitions>()

    @Volatile
    private var pollingJob: Job? = null

    fun start() {
        if (pollingJob?.isActive == true) {
            return
        }
        store.recoverInFlight(System.currentTimeMillis())
        pollingJob =
            coroutineScope.launch {
                while (isActive) {
                    pumpReadyEntries()
                    delay(pollIntervalMs)
                }
            }
    }

    private suspend fun pumpReadyEntries() {
        val claimed = store.claimReady(System.currentTimeMillis(), maxClaimBatchSize)
        claimed.forEach { entry ->
            val partition =
                routePartitions
                    .computeIfAbsent(entry.route, ::createRoutePartitions)
                    .partitions[partitionIndexForRoom(entry.roomId, partitionCount)]
            val sendResult = partition.channel.trySend(entry)
            if (sendResult.isFailure) {
                store.requeueClaim(
                    id = entry.id,
                    nextAttemptAt = System.currentTimeMillis() + pollIntervalMs,
                    lastError = "partition queue saturated: route=${entry.route}, partition=${partition.index}",
                )
            }
        }
    }

    private fun createRoutePartitions(route: String): RoutePartitions =
        RoutePartitions(
            route = route,
            partitions =
                List(partitionCount.coerceAtLeast(1)) { index ->
                    val channel = Channel<StoredWebhookOutboxEntry>(partitionQueueCapacity)
                    val job =
                        coroutineScope.launch {
                            for (entry in channel) {
                                processEntry(entry)
                            }
                        }
                    PartitionChannel(index = index, channel = channel, job = job)
                },
        )

    private suspend fun processEntry(entry: StoredWebhookOutboxEntry) {
        val url = config.webhookEndpointFor(entry.route).takeIf { it.isNotBlank() }
        if (url.isNullOrBlank()) {
            store.markDead(entry.id, "no webhook URL configured for route=${entry.route}")
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
                scheduleRetryOrDead(entry, error.message)
                return
            }

        when {
            statusCode in 200..299 -> store.markSent(entry.id)
            shouldRetryStatus(statusCode) -> scheduleRetryOrDead(entry, "status=$statusCode")
            else -> store.markDead(entry.id, "status=$statusCode")
        }
    }

    private fun scheduleRetryOrDead(
        entry: StoredWebhookOutboxEntry,
        reason: String?,
    ) {
        when (
            val retrySchedule =
                nextDeliveryRetrySchedule(
                    attempt = entry.attemptCount,
                    maxDeliveryAttempts = maxDeliveryAttempts,
                    backoffDelayProvider = backoffDelayProvider,
                )
        ) {
            is DeliveryRetrySchedule.RetryAttempt ->
                store.markRetry(
                    id = entry.id,
                    nextAttemptAt = System.currentTimeMillis() + retrySchedule.delayMs,
                    lastError = reason,
                )

            is DeliveryRetrySchedule.Exhausted ->
                store.markDead(
                    id = entry.id,
                    lastError = reason ?: "delivery attempts exhausted",
                )
        }
    }

    override fun close() {
        val job = pollingJob
        pollingJob = null
        runBlocking {
            job?.cancelAndJoin()
            routePartitions.values.forEach { route ->
                route.partitions.forEach { partition ->
                    partition.channel.close()
                    partition.job.cancelAndJoin()
                }
            }
            scopeJob.cancelAndJoin()
        }
        clientFactory
            .clientFor("http://127.0.0.1")
            .dispatcher.executorService
            .shutdownNow()
        clientFactory.clientFor("http://127.0.0.1").connectionPool.evictAll()
        store.close()
    }

    private data class RoutePartitions(
        val route: String,
        val partitions: List<PartitionChannel>,
    )

    private data class PartitionChannel(
        val index: Int,
        val channel: Channel<StoredWebhookOutboxEntry>,
        val job: Job,
    )
}
