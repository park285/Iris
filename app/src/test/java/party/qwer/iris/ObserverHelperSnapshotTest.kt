package party.qwer.iris

import kotlinx.serialization.json.JsonPrimitive
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.delivery.webhook.RoutingResult
import party.qwer.iris.model.QueryColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun stubResult(
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

class ObserverHelperSnapshotTest {
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
    fun `checkChange does not run snapshot diff during quiet polling`() {
        val checkpointStore = SnapshotTestCheckpointStore(initial = mapOf("chat_logs" to 10L))
        val chatLogRepo = SnapshotTestChatLogRepository(latestLogId = 10L, polledLogs = emptyList())
        val memberRepoFixture =
            snapshotMemberRepository(
                mapOf(
                    100L to
                        listOf(
                            snapshot(chatId = 100L, members = setOf(1L)),
                            snapshot(chatId = 100L, members = setOf(1L, 2L)),
                        ),
                ),
            )
        val snapshotManager = CountingSnapshotManager()
        val helper =
            ObserverHelper(
                db = chatLogRepo,
                config = config,
                memberRepo = memberRepoFixture.build(),
                snapshotManager = snapshotManager,
                sseEventBus = SseEventBus(bufferSize = 10),
                checkpointStore = checkpointStore,
                routingGateway = RecordingRoutingGateway(),
            )

        repeat(3) { helper.checkChange() }

        assertEquals(0, snapshotManager.diffCalls)
    }

    @Test
    fun `dirty snapshot diff processes only marked rooms`() {
        val checkpointStore = SnapshotTestCheckpointStore(initial = mapOf("chat_logs" to 10L))
        val memberRepoFixture =
            snapshotMemberRepository(
                mapOf(
                    100L to
                        listOf(
                            snapshot(chatId = 100L, members = setOf(1L), nicknames = mapOf(1L to "Alice")),
                            snapshot(chatId = 100L, members = setOf(1L, 3L), nicknames = mapOf(1L to "Alice", 3L to "Cara")),
                        ),
                    200L to
                        listOf(
                            snapshot(chatId = 200L, members = setOf(2L), nicknames = mapOf(2L to "Bob")),
                            snapshot(chatId = 200L, members = setOf(2L, 4L), nicknames = mapOf(2L to "Bob", 4L to "Drew")),
                        ),
                ),
            )
        val snapshotManager = CountingSnapshotManager()
        val bus = SseEventBus(bufferSize = 10)
        val routingGateway = RecordingRoutingGateway()
        val helper =
            ObserverHelper(
                db = SnapshotTestChatLogRepository(latestLogId = 10L),
                config = config,
                memberRepo = memberRepoFixture.build(),
                snapshotManager = snapshotManager,
                sseEventBus = bus,
                checkpointStore = checkpointStore,
                routingGateway = routingGateway,
            )

        helper.seedSnapshotCache()
        helper.markRoomDirty(100L)
        helper.markRoomDirty(100L)
        helper.markRoomDirty(200L)
        helper.runDirtySnapshotDiff(maxRoomsPerTick = 1)

        assertEquals(listOf(100L), snapshotManager.diffedChatIds)
        assertEquals(1, bus.replayFrom(0).size)
        assertEquals(listOf("100"), routingGateway.commands.map { it.room })

        helper.runDirtySnapshotDiff(maxRoomsPerTick = 10)

        assertEquals(listOf(100L, 200L), snapshotManager.diffedChatIds)
        assertEquals(2, bus.replayFrom(0).size)
        assertEquals(listOf("100", "200"), routingGateway.commands.map { it.room })
        assertEquals(listOf(100L, 200L, 100L, 200L), memberRepoFixture.snapshotCalls)
        assertTrue(bus.replayFrom(0).all { (_, payload) -> payload.contains("\"event\":\"join\"") })
    }

    @Test
    fun `markAllRoomsDirty marks all seeded rooms as dirty`() {
        val helper =
            ObserverHelper(
                db = SnapshotTestChatLogRepository(latestLogId = 10L),
                config = config,
                memberRepo =
                    snapshotMemberRepository(
                        mapOf(
                            100L to listOf(snapshot(chatId = 100L, members = setOf(1L))),
                            200L to listOf(snapshot(chatId = 200L, members = setOf(2L))),
                            300L to listOf(snapshot(chatId = 300L, members = setOf(3L))),
                        ),
                    ).build(),
                snapshotManager = CountingSnapshotManager(),
                sseEventBus = SseEventBus(bufferSize = 10),
                checkpointStore = SnapshotTestCheckpointStore(initial = mapOf("chat_logs" to 10L)),
                routingGateway = RecordingRoutingGateway(),
            )

        helper.seedSnapshotCache()
        helper.markAllRoomsDirty()

        assertEquals(3, helper.dirtyRoomCount())

        helper.runDirtySnapshotDiff(maxRoomsPerTick = 10)

        assertEquals(0, helper.dirtyRoomCount())
    }

    @Test
    fun `markAllRoomsDirty emits nickname change event during reconcile`() {
        val memberRepoFixture =
            snapshotMemberRepository(
                mapOf(
                    100L to
                        listOf(
                            snapshot(chatId = 100L, members = setOf(1L), nicknames = mapOf(1L to "Alice")),
                            snapshot(chatId = 100L, members = setOf(1L), nicknames = mapOf(1L to "Alice Updated")),
                        ),
                ),
            )
        val bus = SseEventBus(bufferSize = 10)
        val routingGateway = RecordingRoutingGateway()
        val helper =
            ObserverHelper(
                db = SnapshotTestChatLogRepository(latestLogId = 10L),
                config = config,
                memberRepo = memberRepoFixture.build(),
                snapshotManager = RoomSnapshotManager(),
                sseEventBus = bus,
                checkpointStore = SnapshotTestCheckpointStore(initial = mapOf("chat_logs" to 10L)),
                routingGateway = routingGateway,
            )

        helper.seedSnapshotCache()
        helper.markAllRoomsDirty()
        helper.runDirtySnapshotDiff(maxRoomsPerTick = 10)

        val replay = bus.replayFrom(0)
        assertEquals(1, replay.size)
        assertTrue(replay.single().second.contains("\"oldNickname\":\"Alice\""))
        assertTrue(replay.single().second.contains("\"newNickname\":\"Alice Updated\""))
        assertEquals(1, routingGateway.commands.size)
        assertEquals("100", routingGateway.commands.single().room)
        assertTrue(
            routingGateway.commands
                .single()
                .text
                .contains("\"oldNickname\":\"Alice\""),
        )
        assertTrue(
            routingGateway.commands
                .single()
                .text
                .contains("\"newNickname\":\"Alice Updated\""),
        )
    }

    @Test
    fun `dirtyRoomCount reflects pending dirty rooms accurately`() {
        val helper =
            ObserverHelper(
                db = SnapshotTestChatLogRepository(latestLogId = 10L),
                config = config,
                memberRepo =
                    snapshotMemberRepository(
                        mapOf(
                            100L to
                                listOf(
                                    snapshot(chatId = 100L, members = setOf(1L)),
                                    snapshot(chatId = 100L, members = setOf(1L, 10L)),
                                ),
                            200L to
                                listOf(
                                    snapshot(chatId = 200L, members = setOf(2L)),
                                    snapshot(chatId = 200L, members = setOf(2L, 20L)),
                                ),
                        ),
                    ).build(),
                snapshotManager = CountingSnapshotManager(),
                sseEventBus = SseEventBus(bufferSize = 10),
                checkpointStore = SnapshotTestCheckpointStore(initial = mapOf("chat_logs" to 10L)),
                routingGateway = RecordingRoutingGateway(),
            )

        helper.seedSnapshotCache()
        helper.markRoomDirty(100L)
        helper.markRoomDirty(200L)

        assertEquals(2, helper.dirtyRoomCount())

        helper.runDirtySnapshotDiff(maxRoomsPerTick = 1)

        assertEquals(1, helper.dirtyRoomCount())
    }

    private fun snapshot(
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
}

private class CountingSnapshotManager : RoomSnapshotManager() {
    var diffCalls = 0
    val diffedChatIds = mutableListOf<Long>()

    override fun diff(
        prev: RoomSnapshotData,
        curr: RoomSnapshotData,
    ): List<Any> {
        diffCalls++
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

private class RecordingRoutingGateway : RoutingGateway {
    val commands = mutableListOf<RoutingCommand>()

    override fun route(command: RoutingCommand): RoutingResult {
        commands += command
        return RoutingResult.ACCEPTED
    }

    override fun close() {}
}

private class SnapshotTestCheckpointStore(
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

private class SnapshotTestChatLogRepository(
    var latestLogId: Long = 1L,
    var polledLogs: List<KakaoDB.ChatLogEntry> = emptyList(),
) : ChatLogRepository {
    override fun pollChatLogsAfter(
        afterLogId: Long,
        limit: Int,
    ): List<KakaoDB.ChatLogEntry> = polledLogs

    override fun resolveSenderName(userId: Long): String = userId.toString()

    override fun resolveRoomMetadata(chatId: Long): KakaoDB.RoomMetadata = KakaoDB.RoomMetadata()

    override fun latestLogId(): Long = latestLogId

    override fun executeQuery(
        sqlQuery: String,
        bindArgs: Array<String?>?,
        maxRows: Int,
    ): QueryExecutionResult = QueryExecutionResult(columns = emptyList(), rows = emptyList())
}

private class SnapshotMemberRepositoryFixture(
    private val snapshotsByChatId: Map<Long, List<RoomSnapshotData>>,
) {
    val snapshotCalls = mutableListOf<Long>()
    private val snapshotIndexes = mutableMapOf<Long, Int>()
    private var currentSnapshotByLinkId = mutableMapOf<Long, RoomSnapshotData>()

    fun build(): MemberRepository =
        MemberRepository(
            executeQueryTyped =
                legacyQuery { sqlQuery, bindArgs, _ ->
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
                            snapshotCalls += chatId
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

private fun snapshotMemberRepository(snapshots: Map<Long, List<RoomSnapshotData>>): SnapshotMemberRepositoryFixture = SnapshotMemberRepositoryFixture(snapshots)
