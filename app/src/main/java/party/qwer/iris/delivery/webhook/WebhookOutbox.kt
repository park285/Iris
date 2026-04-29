package party.qwer.iris.delivery.webhook

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import party.qwer.iris.CommandParser
import party.qwer.iris.ConfigProvider
import party.qwer.iris.PathUtils
import party.qwer.iris.model.StoredWebhookOutboxEntry
import party.qwer.iris.model.WebhookOutboxFileState
import party.qwer.iris.model.WebhookOutboxStatus
import party.qwer.iris.nativecore.NativeCoreHolder
import party.qwer.iris.nativecore.NativeIngressPlan
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.absoluteValue

internal data class PendingWebhookOutboxEntry(
    val roomId: Long,
    val route: String,
    val messageId: String,
    val payloadJson: String,
)

internal interface WebhookOutboxStore : Closeable {
    fun enqueue(entry: PendingWebhookOutboxEntry): Boolean

    fun claimReady(
        nowEpochMs: Long,
        limit: Int,
    ): List<StoredWebhookOutboxEntry>

    fun markSent(id: Long)

    fun markRetry(
        id: Long,
        nextAttemptAt: Long,
        lastError: String?,
    )

    fun requeueClaim(
        id: Long,
        nextAttemptAt: Long,
        lastError: String?,
    )

    fun markDead(
        id: Long,
        lastError: String?,
    )

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
        if (state.entries.any { it.messageId == entry.messageId }) {
            return true
        }
        val now = clock()
        val stored =
            StoredWebhookOutboxEntry(
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
        writeState(
            state.copy(
                nextId = state.nextId + 1,
                entries = state.entries + stored,
            ),
        )
        return true
    }

    @Synchronized
    override fun claimReady(
        nowEpochMs: Long,
        limit: Int,
    ): List<StoredWebhookOutboxEntry> {
        val state = readState()
        val readyEntries =
            state.entries
                .asSequence()
                .filter { entry ->
                    (entry.status == WebhookOutboxStatus.PENDING || entry.status == WebhookOutboxStatus.RETRY) &&
                        entry.nextAttemptAt <= nowEpochMs
                }.sortedBy { it.id }
                .take(limit)
                .toList()
        if (readyEntries.isEmpty()) {
            return emptyList()
        }
        val readyIds = readyEntries.map { it.id }.toSet()
        val updatedEntries =
            state.entries.map { entry ->
                if (entry.id in readyIds) {
                    entry.copy(
                        status = WebhookOutboxStatus.SENDING,
                        updatedAt = nowEpochMs,
                    )
                } else {
                    entry
                }
            }
        writeState(state.copy(entries = updatedEntries))
        return updatedEntries.filter { it.id in readyIds }
    }

    @Synchronized
    override fun markSent(id: Long) {
        updateEntry(id) { entry ->
            entry.copy(
                status = WebhookOutboxStatus.SENT,
                updatedAt = clock(),
                lastError = null,
            )
        }
    }

    @Synchronized
    override fun markRetry(
        id: Long,
        nextAttemptAt: Long,
        lastError: String?,
    ) {
        updateEntry(id) { entry ->
            entry.copy(
                status = WebhookOutboxStatus.RETRY,
                attemptCount = entry.attemptCount + 1,
                nextAttemptAt = nextAttemptAt,
                lastError = lastError,
                updatedAt = clock(),
            )
        }
    }

    @Synchronized
    override fun requeueClaim(
        id: Long,
        nextAttemptAt: Long,
        lastError: String?,
    ) {
        updateEntry(id) { entry ->
            entry.copy(
                status = WebhookOutboxStatus.RETRY,
                nextAttemptAt = nextAttemptAt,
                lastError = lastError,
                updatedAt = clock(),
            )
        }
    }

    @Synchronized
    override fun markDead(
        id: Long,
        lastError: String?,
    ) {
        updateEntry(id) { entry ->
            entry.copy(
                status = WebhookOutboxStatus.DEAD,
                attemptCount = entry.attemptCount + 1,
                lastError = lastError,
                updatedAt = clock(),
            )
        }
    }

    @Synchronized
    override fun recoverInFlight(nowEpochMs: Long) {
        val state = readState()
        val updatedEntries =
            state.entries.map { entry ->
                if (entry.status == WebhookOutboxStatus.SENDING) {
                    entry.copy(
                        status = WebhookOutboxStatus.RETRY,
                        nextAttemptAt = nowEpochMs,
                        updatedAt = nowEpochMs,
                    )
                } else {
                    entry
                }
            }
        writeState(state.copy(entries = updatedEntries))
    }

    override fun close() {}

    private fun updateEntry(
        id: Long,
        transform: (StoredWebhookOutboxEntry) -> StoredWebhookOutboxEntry,
    ) {
        val state = readState()
        writeState(
            state.copy(
                entries =
                    state.entries.map { entry ->
                        if (entry.id == id) {
                            transform(entry)
                        } else {
                            entry
                        }
                    },
            ),
        )
    }

    private fun readState(): WebhookOutboxFileState {
        if (!file.exists()) {
            return WebhookOutboxFileState()
        }
        return runCatching {
            json.decodeFromString<WebhookOutboxFileState>(file.readText())
        }.getOrElse {
            WebhookOutboxFileState()
        }
    }

    private fun writeState(state: WebhookOutboxFileState) {
        val parentDir = file.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }
        val tempFile = File("${file.absolutePath}.tmp")
        tempFile.writeText(json.encodeToString(state))
        try {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}

internal data class ResolvedWebhookDelivery(
    val roomId: Long,
    val route: String,
    val messageId: String,
    val payloadJson: String,
)

internal fun resolveWebhookDeliveryPlansBatch(
    commands: List<RoutingCommand>,
    config: ConfigProvider,
): List<ResolvedWebhookDelivery?> {
    if (commands.isEmpty()) return emptyList()
    val plans =
        NativeCoreHolder.current().planIngressBatchOrFallback(
            commands = commands,
            commandRoutePrefixes = config.commandRoutePrefixes(),
            imageMessageTypeRoutes = config.imageMessageTypeRoutes(),
            eventTypeRoutes = config.eventTypeRoutes(),
        ) {
            commands.map { command -> resolveWebhookDeliveryPlanKotlin(command, config) }
        }
    return commands.zip(plans).map { (command, plan) ->
        plan.toResolvedWebhookDelivery(command, config)
    }
}

internal fun resolveWebhookDelivery(
    command: RoutingCommand,
    config: ConfigProvider,
): ResolvedWebhookDelivery? {
    val parsedCommand = CommandParser.parse(command.text)
    val targetRoute =
        resolveWebhookRoute(parsedCommand, config)
            ?: resolveEventRoute(command.messageType, config)
            ?: resolveImageRoute(command.messageType, config)
            ?: return null
    val webhookUrl = config.webhookEndpointFor(targetRoute).takeIf { it.isNotBlank() } ?: return null
    val normalizedCommand = command.copy(text = parsedCommand.normalizedText)
    val messageId = buildRoutingMessageId(normalizedCommand, targetRoute)
    return ResolvedWebhookDelivery(
        roomId = normalizedCommand.room.toLongOrNull() ?: -1L,
        route = targetRoute,
        messageId = messageId,
        payloadJson = buildWebhookPayload(normalizedCommand, targetRoute, messageId),
    )
}

private fun resolveWebhookDeliveryPlanKotlin(
    command: RoutingCommand,
    config: ConfigProvider,
): NativeIngressPlan {
    val parsedCommand = CommandParser.parseKotlin(command.text)
    val targetRoute =
        resolveWebhookRouteKotlin(parsedCommand, config)
            ?: resolveEventRouteKotlin(command.messageType, config.eventTypeRoutes())
            ?: resolveImageRouteKotlin(command.messageType, config)
            ?: return NativeIngressPlan(parsedCommand = parsedCommand)
    val normalizedCommand = command.copy(text = parsedCommand.normalizedText)
    val messageId = buildRoutingMessageId(normalizedCommand, targetRoute)
    return NativeIngressPlan(
        parsedCommand = parsedCommand,
        targetRoute = targetRoute,
        messageId = messageId,
        payloadJson = buildWebhookPayloadKotlin(normalizedCommand, targetRoute, messageId),
    )
}

private fun NativeIngressPlan.toResolvedWebhookDelivery(
    command: RoutingCommand,
    config: ConfigProvider,
): ResolvedWebhookDelivery? {
    val route = targetRoute ?: return null
    if (config.webhookEndpointFor(route).isBlank()) return null
    val normalizedCommand = command.copy(text = parsedCommand.normalizedText)
    val resolvedMessageId = messageId ?: buildRoutingMessageId(normalizedCommand, route)
    return ResolvedWebhookDelivery(
        roomId = normalizedCommand.room.toLongOrNull() ?: -1L,
        route = route,
        messageId = resolvedMessageId,
        payloadJson = payloadJson ?: buildWebhookPayloadKotlin(normalizedCommand, route, resolvedMessageId),
    )
}

internal fun partitionIndexForRoom(
    roomId: Long,
    partitionCount: Int,
): Int = roomId.hashCode().absoluteValue % partitionCount.coerceAtLeast(1)
