package party.qwer.iris.ingress

import party.qwer.iris.ChatLogRepository
import party.qwer.iris.ConfigProvider
import party.qwer.iris.KakaoDB
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.delivery.webhook.RoutingResult
import party.qwer.iris.persistence.BatchedCheckpointJournal
import party.qwer.iris.persistence.CheckpointJournal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandIngressServiceTest {
    private val config =
        object : ConfigProvider {
            override val botId = 0L
            override val botName = ""
            override val botSocketPort = 0
            override val inboundSigningSecret = ""
            override val outboundWebhookToken = ""
            override val botControlToken = ""
            override val dbPollingRate = 100L
            override val messageSendRate = 0L
            override val messageSendJitterMax = 0L

            override fun webhookEndpointFor(route: String): String = ""
        }

    @Test
    fun `checkChange initializes lastLogId on first call`() {
        val db = FakeChatLogRepository(latestLogId = 42L)
        val markDirtyCalls = mutableListOf<Long>()
        val ingress =
            CommandIngressService(
                db = db,
                config = config,
                checkpointJournal = FakeCheckpointJournal(),
                routingGateway = FakeRoutingGateway(),
                onMarkDirty = { chatId -> markDirtyCalls.add(chatId) },
            )

        ingress.checkChange()

        assertEquals(42L, ingress.lastObservedLogId())
        assertEquals(null, db.lastPolledAfterLogId)
        assertTrue(markDirtyCalls.isEmpty())
        ingress.close()
    }

    @Test
    fun `checkChange loads persisted lastLogId from journal`() {
        val db = FakeChatLogRepository(latestLogId = 100L)
        val store = FakeCheckpointStore(initial = mapOf("chat_logs" to 5L))
        val journal = BatchedCheckpointJournal(store = store, flushIntervalMs = Long.MAX_VALUE, clock = { 0L })
        val ingress =
            CommandIngressService(
                db = db,
                config = config,
                checkpointJournal = journal,
                routingGateway = FakeRoutingGateway(),
                onMarkDirty = {},
            )

        ingress.checkChange()
        ingress.checkChange()

        assertEquals(5L, ingress.lastObservedLogId())
        assertEquals(5L, db.lastPolledAfterLogId)
        ingress.close()
    }

    @Test
    fun `checkChange advances without flushing durable store before batching interval`() {
        var currentTime = 1_000L
        val store = FakeCheckpointStore(initial = mapOf("chat_logs" to 10L))
        val journal = BatchedCheckpointJournal(store = store, flushIntervalMs = 5_000L, clock = { currentTime })
        val ingress =
            CommandIngressService(
                db =
                    FakeChatLogRepository(
                        latestLogId = 10L,
                        polledLogs = listOf(webhookChatLogEntry(id = 11L, chatId = 100L, message = "!ping")),
                    ),
                config = config,
                checkpointJournal = journal,
                routingGateway = FakeRoutingGateway(result = RoutingResult.ACCEPTED),
                onMarkDirty = {},
            )

        ingress.checkChange()
        ingress.checkChange()

        assertEquals(11L, ingress.lastObservedLogId())
        assertEquals(10L, store.load("chat_logs"))

        currentTime = 7_000L
        ingress.checkChange()

        assertEquals(10L, store.load("chat_logs"))
        journal.flushIfDirty()
        assertEquals(11L, store.load("chat_logs"))
        ingress.close()
    }

    @Test
    fun `checkChange emits markDirty for each new log entry`() {
        val db =
            FakeChatLogRepository(
                latestLogId = 10L,
                polledLogs =
                    listOf(
                        webhookChatLogEntry(id = 11L, chatId = 100L, message = "hello"),
                        webhookChatLogEntry(id = 12L, chatId = 200L, message = "!ping"),
                        webhookChatLogEntry(id = 13L, chatId = 100L, message = "/pong"),
                    ),
            )
        val markDirtyCalls = mutableListOf<Long>()
        val ingress =
            CommandIngressService(
                db = db,
                config = config,
                checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                routingGateway = FakeRoutingGateway(),
                onMarkDirty = { chatId -> markDirtyCalls.add(chatId) },
            )

        ingress.checkChange()
        ingress.checkChange()

        assertEquals(listOf(100L, 200L, 100L), markDirtyCalls)
        ingress.close()
    }

    @Test
    fun `checkChange advances checkpoint after processing`() {
        val journal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L))
        val ingress =
            CommandIngressService(
                db =
                    FakeChatLogRepository(
                        latestLogId = 10L,
                        polledLogs = listOf(webhookChatLogEntry(id = 11L, chatId = 100L, message = "!ping")),
                    ),
                config = config,
                checkpointJournal = journal,
                routingGateway = FakeRoutingGateway(result = RoutingResult.ACCEPTED),
                onMarkDirty = {},
            )

        ingress.checkChange()
        ingress.checkChange()

        assertEquals(11L, journal.saved["chat_logs"])
        assertEquals(listOf("chat_logs" to 11L), journal.advanceCalls)
        ingress.close()
    }

    @Test
    fun `checkChange does not flush checkpoints from ingress hot path`() {
        val journal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L))
        val ingress =
            CommandIngressService(
                db =
                    FakeChatLogRepository(
                        latestLogId = 10L,
                        polledLogs = listOf(webhookChatLogEntry(id = 11L, chatId = 100L, message = "!ping")),
                    ),
                config = config,
                checkpointJournal = journal,
                routingGateway = FakeRoutingGateway(result = RoutingResult.ACCEPTED),
                onMarkDirty = {},
            )

        ingress.checkChange()
        ingress.checkChange()

        assertEquals(0, journal.flushIfDirtyCalls)
        ingress.close()
    }

    @Test
    fun `quiet polling produces no markDirty calls`() {
        val markDirtyCalls = mutableListOf<Long>()
        val ingress =
            CommandIngressService(
                db = FakeChatLogRepository(latestLogId = 10L),
                config = config,
                checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                routingGateway = FakeRoutingGateway(),
                onMarkDirty = { chatId -> markDirtyCalls.add(chatId) },
            )

        repeat(3) { ingress.checkChange() }

        assertTrue(markDirtyCalls.isEmpty())
        ingress.close()
    }
}

private class FakeChatLogRepository(
    var latestLogId: Long = 1L,
    var polledLogs: List<KakaoDB.ChatLogEntry> = emptyList(),
    var roomMetadata: KakaoDB.RoomMetadata = KakaoDB.RoomMetadata(),
) : ChatLogRepository {
    var lastPolledAfterLogId: Long? = null

    override fun pollChatLogsAfter(
        afterLogId: Long,
        limit: Int,
    ): List<KakaoDB.ChatLogEntry> {
        lastPolledAfterLogId = afterLogId
        return polledLogs
    }

    override fun resolveSenderName(userId: Long): String = userId.toString()

    override fun resolveRoomMetadata(chatId: Long): KakaoDB.RoomMetadata = roomMetadata

    override fun latestLogId(): Long = latestLogId
}

private class FakeCheckpointStore(
    initial: Map<String, Long> = emptyMap(),
) : party.qwer.iris.CheckpointStore {
    private val values = initial.toMutableMap()

    override fun load(streamName: String): Long? = values[streamName]

    override fun save(
        streamName: String,
        lastLogId: Long,
    ) {
        values[streamName] = lastLogId
    }
}

private class FakeCheckpointJournal(
    initial: Map<String, Long> = emptyMap(),
) : CheckpointJournal {
    val saved = initial.toMutableMap()
    val advanceCalls = mutableListOf<Pair<String, Long>>()
    var flushIfDirtyCalls = 0

    override fun advance(
        stream: String,
        cursor: Long,
    ) {
        saved[stream] = cursor
        advanceCalls += stream to cursor
    }

    override fun flushIfDirty() {
        flushIfDirtyCalls++
    }

    override fun flushNow() {}

    override fun load(stream: String): Long? = saved[stream]
}

private class FakeRoutingGateway(
    private val result: RoutingResult = RoutingResult.ACCEPTED,
) : RoutingGateway {
    val commands = mutableListOf<RoutingCommand>()

    override fun route(command: RoutingCommand): RoutingResult {
        commands += command
        return result
    }

    override fun close() {}
}

private fun webhookChatLogEntry(
    id: Long,
    chatId: Long,
    message: String,
    userId: Long = 200L,
    createdAt: String = "1711111111",
): KakaoDB.ChatLogEntry =
    KakaoDB.ChatLogEntry(
        id = id,
        chatId = chatId,
        userId = userId,
        message = message,
        metadata = "{\"enc\":0,\"origin\":\"CHATLOG\"}",
        createdAt = createdAt,
    )
