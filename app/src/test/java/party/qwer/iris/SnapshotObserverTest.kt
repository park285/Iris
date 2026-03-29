package party.qwer.iris

import kotlinx.serialization.json.JsonPrimitive
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.delivery.webhook.RoutingResult
import party.qwer.iris.model.QueryColumn
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotObserverTest {
    private val config =
        object : ConfigProvider {
            override val botId = 0L
            override val botName = ""
            override val botSocketPort = 0
            override val botToken = ""
            override val webhookToken = ""
            override val inboundSigningSecret = ""
            override val outboundWebhookToken = ""
            override val botControlToken = ""
            override val dbPollingRate = 100L
            override val messageSendRate = 0L
            override val messageSendJitterMax = 0L

            override fun webhookEndpointFor(route: String): String = ""
        }

    @Test
    fun `full reconcile triggers after configured interval`() {
        val currentTimeMs = AtomicLong(0L)
        val snapshotManager = SnapshotObserverCountingSnapshotManager()
        val helper =
            ObserverHelper(
                db = SnapshotObserverTestChatLogRepository(latestLogId = 10L),
                config = config,
                memberRepo =
                    snapshotObserverMemberRepository(
                        mapOf(
                            100L to
                                listOf(
                                    snapshotObserverSnapshot(chatId = 100L, members = setOf(1L), nicknames = mapOf(1L to "Alice")),
                                    snapshotObserverSnapshot(chatId = 100L, members = setOf(1L), nicknames = mapOf(1L to "Alice Updated")),
                                ),
                        ),
                    ).build(),
                snapshotManager = snapshotManager,
                sseEventBus = SseEventBus(bufferSize = 10),
                checkpointStore = SnapshotObserverCheckpointStore(initial = mapOf("chat_logs" to 10L)),
                routingGateway = SnapshotObserverRecordingRoutingGateway(),
            )
        val observer =
            SnapshotObserver(
                observerHelper = helper,
                intervalMs = 50L,
                maxRoomsPerTick = 32,
                fullReconcileIntervalMs = 200L,
                clock = currentTimeMs::get,
            )

        helper.seedSnapshotCache()
        observer.start()
        currentTimeMs.set(250L)
        waitUntil(timeoutMs = 1_000L) { snapshotManager.diffCalls >= 1 }
        observer.stop()

        assertTrue(snapshotManager.diffCalls >= 1)
        assertEquals(listOf(100L), snapshotManager.diffedChatIds)
    }

    @Test
    fun `adaptive drain increases budget under backlog pressure`() {
        val rooms =
            (1L..256L).associateWith { chatId ->
                listOf(
                    snapshotObserverSnapshot(chatId = chatId, members = setOf(chatId)),
                    snapshotObserverSnapshot(chatId = chatId, members = setOf(chatId, chatId + 10_000L)),
                )
            }
        val snapshotManager = SnapshotObserverCountingSnapshotManager()
        val helper =
            ObserverHelper(
                db = SnapshotObserverTestChatLogRepository(latestLogId = 10L),
                config = config,
                memberRepo = snapshotObserverMemberRepository(rooms).build(),
                snapshotManager = snapshotManager,
                sseEventBus = SseEventBus(bufferSize = 512),
                checkpointStore = SnapshotObserverCheckpointStore(initial = mapOf("chat_logs" to 10L)),
                routingGateway = SnapshotObserverRecordingRoutingGateway(),
            )
        val observer =
            SnapshotObserver(
                observerHelper = helper,
                intervalMs = 5_000L,
                maxRoomsPerTick = 32,
                fullReconcileIntervalMs = 60_000L,
            )

        helper.seedSnapshotCache()
        helper.markAllRoomsDirty()
        observer.start()
        waitUntil(timeoutMs = 1_000L) { snapshotManager.diffCalls >= 128 }
        observer.stop()

        assertEquals(128, snapshotManager.diffCalls)
        assertEquals(128, helper.dirtyRoomCount())
    }

    private fun waitUntil(
        timeoutMs: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition not met within ${timeoutMs}ms")
    }
}

private fun snapshotObserverStubResult(
    columns: List<String>,
    rows: List<Map<String, String?>>,
): QueryExecutionResult {
    val cols = columns.map { QueryColumn(name = it, sqliteType = "TEXT") }
    val jsonRows =
        rows.map { row ->
            columns.map { col ->
                row[col]?.let { JsonPrimitive(it) }
            }
        }
    return QueryExecutionResult(cols, jsonRows)
}

private fun snapshotObserverEmptyResult(): QueryExecutionResult = QueryExecutionResult(emptyList(), emptyList())

private fun snapshotObserverLegacyQuery(block: (String, Array<String?>?, Int) -> List<Map<String, String?>>): (String, Array<String?>?, Int) -> QueryExecutionResult =
    { sql, args, maxRows ->
        val rows = block(sql, args, maxRows)
        val columns =
            rows
                .firstOrNull()
                ?.keys
                ?.toList()
                .orEmpty()
        if (columns.isEmpty()) {
            snapshotObserverEmptyResult()
        } else {
            snapshotObserverStubResult(columns, rows)
        }
    }

private fun snapshotObserverSnapshot(
    chatId: Long,
    members: Set<Long>,
    nicknames: Map<Long, String> = emptyMap(),
): RoomSnapshotData =
    RoomSnapshotData(
        chatId = chatId,
        linkId = chatId + 1000L,
        memberIds = members,
        blindedIds = emptySet(),
        nicknames = nicknames,
        roles = members.associateWith { 2 },
        profileImages = emptyMap(),
    )

private class SnapshotObserverCountingSnapshotManager : RoomSnapshotManager() {
    private val diffCallCounter = AtomicInteger(0)
    val diffCalls: Int
        get() = diffCallCounter.get()
    val diffedChatIds = CopyOnWriteArrayList<Long>()

    override fun diff(
        prev: RoomSnapshotData,
        curr: RoomSnapshotData,
    ): List<Any> {
        diffCallCounter.incrementAndGet()
        diffedChatIds += curr.chatId
        val joined = (curr.memberIds - prev.memberIds).firstOrNull() ?: return emptyList()
        return listOf(
            party.qwer.iris.model.MemberEvent(
                event = "join",
                chatId = curr.chatId,
                linkId = curr.linkId,
                userId = joined,
                nickname = curr.nicknames[joined],
                estimated = false,
                timestamp = 1L,
            ),
        )
    }
}

private class SnapshotObserverRecordingRoutingGateway : RoutingGateway {
    override fun route(command: RoutingCommand): RoutingResult = RoutingResult.ACCEPTED

    override fun close() {}
}

private class SnapshotObserverCheckpointStore(
    initial: Map<String, Long> = emptyMap(),
) : CheckpointStore {
    private val saved = initial.toMutableMap()

    override fun load(streamName: String): Long? = saved[streamName]

    override fun save(
        streamName: String,
        lastLogId: Long,
    ) {
        saved[streamName] = lastLogId
    }
}

private class SnapshotObserverTestChatLogRepository(
    var latestLogId: Long = 1L,
) : ChatLogRepository {
    override fun pollChatLogsAfter(
        afterLogId: Long,
        limit: Int,
    ): List<KakaoDB.ChatLogEntry> = emptyList()

    override fun resolveSenderName(userId: Long): String = userId.toString()

    override fun resolveRoomMetadata(chatId: Long): KakaoDB.RoomMetadata = KakaoDB.RoomMetadata()

    override fun latestLogId(): Long = latestLogId

    override fun executeQuery(
        sqlQuery: String,
        bindArgs: Array<String?>?,
        maxRows: Int,
    ): QueryExecutionResult = QueryExecutionResult(columns = emptyList(), rows = emptyList())
}

private class SnapshotObserverMemberRepositoryFixture(
    private val snapshotsByChatId: Map<Long, List<RoomSnapshotData>>,
) {
    private val snapshotIndexes = mutableMapOf<Long, Int>()
    private var currentSnapshotByLinkId = mutableMapOf<Long, RoomSnapshotData>()

    fun build(): MemberRepository =
        MemberRepository(
            executeQueryTyped =
                snapshotObserverLegacyQuery { sqlQuery, bindArgs, _ ->
                    when {
                        sqlQuery.contains("FROM chat_rooms cr") ->
                            snapshotsByChatId.keys.map { chatId ->
                                val snapshot = snapshotsByChatId.getValue(chatId).first()
                                mapOf(
                                    "id" to chatId.toString(),
                                    "type" to "OM",
                                    "active_members_count" to snapshot.memberIds.size.toString(),
                                    "link_id" to snapshot.linkId?.toString(),
                                    "meta" to null,
                                    "members" to null,
                                    "link_name" to null,
                                    "link_url" to null,
                                    "member_limit" to null,
                                    "searchable" to null,
                                    "bot_role" to null,
                                )
                            }
                        sqlQuery == "SELECT members, blinded_member_ids, link_id FROM chat_rooms WHERE id = ?" -> {
                            val chatId = bindArgs?.firstOrNull()?.toLongOrNull() ?: 0L
                            val snapshot = nextSnapshot(chatId)
                            snapshot.linkId?.let { linkId ->
                                currentSnapshotByLinkId[linkId] = snapshot
                            }
                            listOf(
                                mapOf(
                                    "members" to snapshot.memberIds.joinToString(prefix = "[", postfix = "]"),
                                    "blinded_member_ids" to snapshot.blindedIds.joinToString(prefix = "[", postfix = "]"),
                                    "link_id" to snapshot.linkId?.toString(),
                                ),
                            )
                        }
                        sqlQuery == "SELECT user_id, nickname, link_member_type, profile_image_url, enc FROM db2.open_chat_member WHERE link_id = ?" -> {
                            val linkId = bindArgs?.firstOrNull()?.toLongOrNull()
                            if (linkId == null) {
                                emptyList()
                            } else {
                                val snapshot = currentSnapshotByLinkId.getValue(linkId)
                                snapshot.memberIds.map { userId ->
                                    mapOf(
                                        "user_id" to userId.toString(),
                                        "nickname" to snapshot.nicknames[userId],
                                        "link_member_type" to snapshot.roles[userId]?.toString(),
                                        "profile_image_url" to snapshot.profileImages[userId],
                                        "enc" to "0",
                                    )
                                }
                            }
                        }
                        else -> emptyList()
                    }
                },
            decrypt = { _, raw, _ -> raw },
            botId = 0L,
        )

    private fun nextSnapshot(chatId: Long): RoomSnapshotData {
        val snapshots = snapshotsByChatId.getValue(chatId)
        val currentIndex = snapshotIndexes[chatId] ?: 0
        val safeIndex = minOf(currentIndex, snapshots.lastIndex)
        snapshotIndexes[chatId] = safeIndex + 1
        return snapshots[safeIndex]
    }
}

private fun snapshotObserverMemberRepository(
    snapshots: Map<Long, List<RoomSnapshotData>>,
): SnapshotObserverMemberRepositoryFixture = SnapshotObserverMemberRepositoryFixture(snapshots)
