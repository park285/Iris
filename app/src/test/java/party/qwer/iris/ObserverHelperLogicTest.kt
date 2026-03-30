package party.qwer.iris

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.delivery.webhook.RoutingResult
import party.qwer.iris.ingress.CommandIngressService
import party.qwer.iris.persistence.BatchedCheckpointJournal
import party.qwer.iris.persistence.CheckpointJournal
import party.qwer.iris.snapshot.RoomSnapshotReadResult
import party.qwer.iris.snapshot.RoomSnapshotReader
import party.qwer.iris.snapshot.SnapshotCoordinator
import party.qwer.iris.snapshot.SnapshotEventEmitter
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.KakaoDbSqlClient
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.MemberIdentityQueries
import party.qwer.iris.storage.ObservedProfileQueries
import party.qwer.iris.storage.QuerySpec
import party.qwer.iris.storage.RoomDirectoryQueries
import party.qwer.iris.storage.RoomStatsQueries
import party.qwer.iris.storage.SqlClient
import party.qwer.iris.storage.ThreadQueries
import party.qwer.iris.storage.UserId
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObserverHelperLogicTest {
    private val configPath = "/tmp/iris-observer-helper-test-config.json"
    private val config = ConfigManager(configPath = configPath)
    private val originalBotId = config.botId
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() {
        config.botId = originalBotId
        File(configPath).delete()
    }

    @Test
    fun `skips only sync style origins for none commands`() {
        val cases =
            listOf(
                Triple("SYNCMSG", CommandKind.NONE, true),
                Triple("SYNCMSG", CommandKind.WEBHOOK, false),
                Triple("CHATLOG", CommandKind.NONE, false),
                Triple(null, CommandKind.NONE, false),
            )

        cases.forEach { (origin, kind, expected) ->
            val parsedCommand = ParsedCommand(kind = kind, normalizedText = "message")
            assertEquals(expected, shouldSkipOrigin(origin, parsedCommand))
        }
    }

    @Test
    fun `bot id zero disables own bot message detection`() {
        config.botId = 0L

        assertFalse(isOwnBotMessage(0L, config.botId))
        assertFalse(isOwnBotMessage(123L, config.botId))
    }

    @Test
    fun `returns true only when user id matches configured bot id`() {
        config.botId = 42L

        assertTrue(isOwnBotMessage(42L, config.botId))
        assertFalse(isOwnBotMessage(41L, config.botId))
    }

    @Test
    fun `stableLogHash is stable for same input and differs for different input`() {
        val first = "!ping".stableLogHash()
        val second = "!ping".stableLogHash()
        val different = "!pong".stableLogHash()

        assertEquals(first, second)
        assertTrue(first != different)
    }

    @Test
    fun `snapshot diff primes on first run and waits for dirty snapshot processing`() {
        val chatLogRepo = FakeChatLogRepository()
        val snapshotReader =
            FakeRoomSnapshotReader(
                rooms = listOf(100L),
                snapshots =
                    mapOf(
                        100L to
                            listOf(
                                RoomSnapshotData(
                                    chatId = ChatId(100L),
                                    linkId = LinkId(200L),
                                    memberIds = setOf(UserId(1L)),
                                    blindedIds = emptySet(),
                                    nicknames = mapOf(UserId(1L) to "Alice"),
                                    roles = mapOf(UserId(1L) to 2),
                                    profileImages = emptyMap(),
                                ),
                                RoomSnapshotData(
                                    chatId = ChatId(100L),
                                    linkId = LinkId(200L),
                                    memberIds = setOf(UserId(1L), UserId(2L)),
                                    blindedIds = emptySet(),
                                    nicknames = mapOf(UserId(1L) to "Alice", UserId(2L) to "Bob"),
                                    roles = mapOf(UserId(1L) to 2, UserId(2L) to 2),
                                    profileImages = emptyMap(),
                                ),
                            ),
                    ),
            )
        val bus = SseEventBus(bufferSize = 10)
        val helper =
            buildObserverHelper(
                db = chatLogRepo,
                bus = bus,
                checkpointStore = FakeCheckpointStore(),
                routingGateway = FakeRoutingGateway(),
                snapshotReader = snapshotReader,
            )

        helper.checkChange()
        chatLogRepo.polledLogs = emptyList()
        helper.checkChange()
        assertTrue(bus.replayFrom(0).isEmpty())

        helper.seedSnapshotCache()
        helper.markRoomDirty(100L)
        helper.runDirtySnapshotDiff()
        waitUntil { bus.replayFrom(0).isNotEmpty() }
        val replay = bus.replayFrom(0)

        assertEquals(1, replay.size)
        val eventJson = json.parseToJsonElement(replay.single().second).jsonObject
        assertEquals("join", eventJson.getValue("event").jsonPrimitive.content)
        assertEquals("2", eventJson.getValue("userId").jsonPrimitive.content)

        helper.close()
    }

    @Test
    fun `initial check uses persisted checkpoint when available`() {
        val chatLogRepo = FakeChatLogRepository(latestLogId = 100L)
        val checkpointStore = FakeCheckpointStore(initial = mapOf("chat_logs" to 5L))
        val bundle =
            buildIngressService(
                db = chatLogRepo,
                checkpointStore = checkpointStore,
                routingGateway = FakeRoutingGateway(),
            )

        bundle.ingress.checkChange()
        bundle.ingress.checkChange()

        assertEquals(5L, chatLogRepo.lastPolledAfterLogId)
        bundle.ingress.close()
    }

    @Test
    fun `initial check seeds checkpoint from latest log id when absent`() {
        val checkpointStore = FakeCheckpointStore()
        val chatLogRepo = FakeChatLogRepository(latestLogId = 42L)
        val bundle =
            buildIngressService(
                db = chatLogRepo,
                checkpointStore = checkpointStore,
                routingGateway = FakeRoutingGateway(),
            )

        bundle.ingress.checkChange()
        bundle.journal.flushNow()

        assertEquals(42L, checkpointStore.saved["chat_logs"])
        assertEquals(null, chatLogRepo.lastPolledAfterLogId)
        bundle.ingress.close()
    }

    @Test
    fun `accepted command advances persisted checkpoint`() {
        val checkpointStore = FakeCheckpointStore(initial = mapOf("chat_logs" to 10L))
        val chatLogRepo =
            FakeChatLogRepository(
                latestLogId = 10L,
                polledLogs = listOf(webhookChatLogEntry(id = 11L, message = "!ping")),
            )
        val bundle =
            buildIngressService(
                db = chatLogRepo,
                checkpointStore = checkpointStore,
                routingGateway = FakeRoutingGateway(result = RoutingResult.ACCEPTED),
            )

        bundle.ingress.checkChange()
        bundle.ingress.checkChange()
        bundle.journal.flushNow()

        assertEquals(11L, checkpointStore.saved["chat_logs"])
        bundle.ingress.close()
    }

    @Test
    fun `retry later keeps persisted checkpoint unchanged`() {
        val checkpointStore = FakeCheckpointStore(initial = mapOf("chat_logs" to 10L))
        val chatLogRepo =
            FakeChatLogRepository(
                latestLogId = 10L,
                polledLogs = listOf(webhookChatLogEntry(id = 11L, message = "!ping")),
            )
        val bundle =
            buildIngressService(
                db = chatLogRepo,
                checkpointStore = checkpointStore,
                routingGateway = FakeRoutingGateway(result = RoutingResult.RETRY_LATER),
            )

        bundle.ingress.checkChange()
        bundle.ingress.checkChange()
        bundle.journal.flushNow()

        assertEquals(10L, checkpointStore.saved["chat_logs"])
        bundle.ingress.close()
    }

    @Test
    fun `routing uses member repository display name when available`() {
        val checkpointStore = FakeCheckpointStore(initial = mapOf("chat_logs" to 10L))
        val chatLogRepo =
            FakeChatLogRepository(
                latestLogId = 10L,
                polledLogs =
                    listOf(
                        webhookChatLogEntry(
                            id = 11L,
                            chatId = 366795577484293L,
                            userId = 203887151L,
                            message = "!ping",
                        ),
                    ),
                roomMetadata = KakaoDB.RoomMetadata(type = "MultiChat", linkId = ""),
            )
        val memberRepo =
            buildRepoFromLegacy(
                legacyQuery { sqlQuery, bindArgs, _ ->
                    when {
                        sqlQuery.contains("SELECT id, name, enc FROM db2.friends WHERE id IN (?)") &&
                            bindArgs?.toList() == listOf("203887151") -> emptyList()
                        sqlQuery.contains("FROM db3.observed_profile_user_links") &&
                            bindArgs?.toList() == listOf("366795577484293", "203887151") ->
                            listOf(mapOf("user_id" to "203887151", "display_name" to "재균"))
                        else -> emptyList()
                    }
                },
                decrypt = { _, raw, _ -> raw },
                botId = config.botId,
            )
        val routingGateway = FakeRoutingGateway(result = RoutingResult.ACCEPTED)
        val bundle =
            buildIngressService(
                db = chatLogRepo,
                checkpointStore = checkpointStore,
                routingGateway = routingGateway,
                memberRepository = memberRepo,
            )

        bundle.ingress.checkChange()
        bundle.ingress.checkChange()

        assertEquals("재균", routingGateway.commands.single().sender)
        bundle.ingress.close()
    }

    @Test
    fun `process log entry triggers timestamp correlation learning`() {
        val checkpointStore = FakeCheckpointStore(initial = mapOf("chat_logs" to 10L))
        val chatLogRepo =
            FakeChatLogRepository(
                latestLogId = 10L,
                polledLogs =
                    listOf(
                        webhookChatLogEntry(
                            id = 11L,
                            chatId = 123L,
                            userId = 456L,
                            message = "!ping",
                            createdAt = "1711111111",
                        ),
                    ),
            )
        val calls = mutableListOf<Triple<Long, Long, Long>>()
        val bundle =
            buildIngressService(
                db = chatLogRepo,
                checkpointStore = checkpointStore,
                routingGateway = FakeRoutingGateway(result = RoutingResult.ACCEPTED),
                learnFromTimestampCorrelation = { chatId, userId, messageCreatedAtMs ->
                    calls += Triple(chatId, userId, messageCreatedAtMs)
                },
            )

        bundle.ingress.checkChange()
        bundle.ingress.checkChange()

        assertEquals(listOf(Triple(123L, 456L, 1_711_111_111_000L)), calls)
        bundle.ingress.close()
    }

    private fun buildObserverHelper(
        db: FakeChatLogRepository,
        bus: SseEventBus,
        checkpointStore: FakeCheckpointStore,
        routingGateway: FakeRoutingGateway,
        snapshotReader: RoomSnapshotReader,
    ): ObserverHelper {
        val journal = BatchedCheckpointJournal(store = checkpointStore, flushIntervalMs = Long.MAX_VALUE, clock = { 0L })
        val coordinator =
            SnapshotCoordinator(
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
                roomSnapshotReader = snapshotReader,
                diffEngine = RoomSnapshotManager(),
                emitter = SnapshotEventEmitter(bus, routingGateway),
            )
        val ingress =
            CommandIngressService(
                db = db,
                config = config,
                checkpointJournal = journal,
                routingGateway = routingGateway,
                onMarkDirty = { chatId ->
                    kotlinx.coroutines.runBlocking {
                        coordinator.send(
                            party.qwer.iris.snapshot.SnapshotCommand
                                .MarkDirty(ChatId(chatId)),
                        )
                    }
                },
            )
        return ObserverHelper(ingress, coordinator, journal)
    }

    private fun buildIngressService(
        db: FakeChatLogRepository,
        checkpointStore: FakeCheckpointStore,
        routingGateway: FakeRoutingGateway,
        memberRepository: MemberRepository? = null,
        learnFromTimestampCorrelation: ((Long, Long, Long) -> Unit)? = null,
    ): IngressBundle {
        val journal = BatchedCheckpointJournal(store = checkpointStore, flushIntervalMs = Long.MAX_VALUE, clock = { 0L })
        val ingress =
            CommandIngressService(
                db = db,
                config = config,
                checkpointJournal = journal,
                memberRepo = memberRepository,
                routingGateway = routingGateway,
                learnFromTimestampCorrelation = learnFromTimestampCorrelation,
                onMarkDirty = {},
            )
        return IngressBundle(ingress = ingress, journal = journal)
    }

    private fun waitUntil(
        timeoutMs: Long = 1_000L,
        condition: () -> Boolean,
    ) = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeout(timeoutMs) {
            while (!condition()) {
                kotlinx.coroutines.delay(10L)
            }
        }
    }
}

private data class IngressBundle(
    val ingress: CommandIngressService,
    val journal: CheckpointJournal,
)

private class FakeRoomSnapshotReader(
    private val rooms: List<Long>,
    private val snapshots: Map<Long, List<RoomSnapshotData>>,
) : RoomSnapshotReader {
    private val indexes = mutableMapOf<Long, Int>()

    override fun listRoomChatIds(): List<ChatId> = rooms.map(::ChatId)

    override fun snapshot(chatId: ChatId): RoomSnapshotReadResult {
        val roomSnapshots = snapshots.getValue(chatId.value)
        val currentIndex = indexes[chatId.value] ?: 0
        val safeIndex = minOf(currentIndex, roomSnapshots.lastIndex)
        indexes[chatId.value] = safeIndex + 1
        return RoomSnapshotReadResult.Present(roomSnapshots[safeIndex])
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

    override fun executeQuery(
        sqlQuery: String,
        bindArgs: Array<String?>?,
        maxRows: Int,
    ): QueryExecutionResult = QueryExecutionResult(columns = emptyList(), rows = emptyList())
}

private class FakeCheckpointStore(
    initial: Map<String, Long> = emptyMap(),
) : CheckpointStore {
    val saved = initial.toMutableMap()

    override fun load(streamName: String): Long? = saved[streamName]

    override fun save(
        streamName: String,
        lastLogId: Long,
    ) {
        saved[streamName] = lastLogId
    }
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

private fun stubResult(
    columns: List<String>,
    rows: List<Map<String, String?>>,
): QueryExecutionResult {
    val cols =
        columns.map {
            party.qwer.iris.model
                .QueryColumn(name = it, sqliteType = "TEXT")
        }
    val jsonRows =
        rows.map { row ->
            columns.map { col ->
                row[col]?.let { kotlinx.serialization.json.JsonPrimitive(it) }
            }
        }
    return QueryExecutionResult(cols, jsonRows)
}

private fun emptyResult(): QueryExecutionResult = QueryExecutionResult(emptyList(), emptyList())

private fun legacyQuery(block: (String, Array<String?>?, Int) -> List<Map<String, String?>>): (String, Array<String?>?, Int) -> QueryExecutionResult =
    { sql, args, maxRows ->
        val rows = block(sql, args, maxRows)
        val columns =
            rows
                .firstOrNull()
                ?.keys
                ?.toList()
                .orEmpty()
        if (columns.isEmpty()) {
            emptyResult()
        } else {
            stubResult(columns, rows)
        }
    }

// 빈 결과를 반환하는 ThreadQueries 스텁
private val stubThreadQueriesObserverHelper =
    ThreadQueries(
        object : SqlClient {
            override fun <T> query(spec: QuerySpec<T>): List<T> = emptyList()
        },
    )

private fun buildRepoFromLegacy(
    executeQueryTyped: (String, Array<String?>?, Int) -> QueryExecutionResult,
    decrypt: (Int, String, Long) -> String = { _, s, _ -> s },
    botId: Long = 1L,
    learnObservedProfileUserMappings: (Long, Map<Long, String>) -> Unit = { _, _ -> },
): MemberRepository {
    val sqlClient = KakaoDbSqlClient(executeQueryTyped)
    return MemberRepository(
        roomDirectory = RoomDirectoryQueries(sqlClient),
        memberIdentity = MemberIdentityQueries(sqlClient, decrypt, botId),
        observedProfile = ObservedProfileQueries(sqlClient),
        roomStats = RoomStatsQueries(sqlClient),
        threadQueries = stubThreadQueriesObserverHelper,
        decrypt = decrypt,
        botId = botId,
        learnObservedProfileUserMappings = learnObservedProfileUserMappings,
    )
}

private fun webhookChatLogEntry(
    id: Long,
    message: String,
    chatId: Long = 100L,
    userId: Long = 200L,
    createdAt: String = "2026-03-27T00:00:00Z",
): KakaoDB.ChatLogEntry =
    KakaoDB.ChatLogEntry(
        id = id,
        chatId = chatId,
        userId = userId,
        message = message,
        metadata = "{\"enc\":0,\"origin\":\"CHATLOG\"}",
        createdAt = createdAt,
    )
