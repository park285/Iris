package party.qwer.iris

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.delivery.webhook.RoutingResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ObserverHelperLogicTest {
    private val config = ConfigManager(configPath = "/tmp/iris-observer-helper-test-config.json")
    private val originalBotId = config.botId
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() {
        config.botId = originalBotId
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
    fun `command fingerprint equality depends on all fields`() {
        val fingerprint =
            ObserverHelper.CommandFingerprint(
                chatId = 1L,
                userId = 2L,
                createdAt = "2026-03-19T00:00:00Z",
                message = "!ping",
            )
        val sameFingerprint =
            ObserverHelper.CommandFingerprint(
                chatId = 1L,
                userId = 2L,
                createdAt = "2026-03-19T00:00:00Z",
                message = "!ping",
            )
        val differentFingerprint =
            ObserverHelper.CommandFingerprint(
                chatId = 1L,
                userId = 2L,
                createdAt = "2026-03-19T00:00:01Z",
                message = "!ping",
            )

        assertEquals(fingerprint, sameFingerprint)
        assertNotEquals(fingerprint, differentFingerprint)
    }

    @Test
    fun `snapshot diff primes on first run and waits for dirty snapshot processing`() {
        val roomList =
            party.qwer.iris.model.RoomListResponse(
                rooms =
                    listOf(
                        party.qwer.iris.model
                            .RoomSummary(chatId = 100L, type = "OM", linkId = 200L, activeMembersCount = 2),
                    ),
            )
        val initialSnapshot =
            RoomSnapshotData(
                chatId = 100L,
                linkId = 200L,
                memberIds = setOf(1L),
                blindedIds = emptySet(),
                nicknames = mapOf(1L to "Alice"),
                roles = mapOf(1L to 2),
                profileImages = emptyMap(),
            )
        val changedSnapshot =
            RoomSnapshotData(
                chatId = 100L,
                linkId = 200L,
                memberIds = setOf(1L, 2L),
                blindedIds = emptySet(),
                nicknames = mapOf(1L to "Alice", 2L to "Bob"),
                roles = mapOf(1L to 2, 2L to 2),
                profileImages = emptyMap(),
            )
        val chatLogRepo = FakeChatLogRepository()
        val memberRepo = snapshotSequenceMemberRepository(roomList, listOf(initialSnapshot, changedSnapshot))
        val snapshotManager = RoomSnapshotManager()
        val bus = SseEventBus(bufferSize = 10)
        val helper =
            ObserverHelper(
                chatLogRepo,
                config,
                memberRepo,
                snapshotManager,
                bus,
                checkpointStore = FakeCheckpointStore(),
                routingGateway = FakeRoutingGateway(),
            )

        helper.checkChange()
        chatLogRepo.polledLogs = emptyList()
        helper.checkChange()
        assertTrue(bus.replayFrom(0).isEmpty())

        helper.markRoomDirty(100L)
        helper.runDirtySnapshotDiff()
        val replay = bus.replayFrom(0)

        assertEquals(1, replay.size)
        val eventJson = json.parseToJsonElement(replay.single().second).jsonObject
        assertEquals("join", eventJson.getValue("event").jsonPrimitive.content)
        assertEquals("2", eventJson.getValue("userId").jsonPrimitive.content)

        helper.close()
    }

    @Test
    fun `initial check uses persisted checkpoint when available`() {
        val checkpointStore = FakeCheckpointStore(initial = mapOf("chat_logs" to 5L))
        val chatLogRepo = FakeChatLogRepository(latestLogId = 100L)
        val helper =
            ObserverHelper(
                db = chatLogRepo,
                config = config,
                checkpointStore = checkpointStore,
                routingGateway = FakeRoutingGateway(),
            )

        helper.checkChange()
        helper.checkChange()

        assertEquals(5L, chatLogRepo.lastPolledAfterLogId)
        helper.close()
    }

    @Test
    fun `initial check seeds checkpoint from latest log id when absent`() {
        val checkpointStore = FakeCheckpointStore()
        val chatLogRepo = FakeChatLogRepository(latestLogId = 42L)
        val helper =
            ObserverHelper(
                db = chatLogRepo,
                config = config,
                checkpointStore = checkpointStore,
                routingGateway = FakeRoutingGateway(),
            )

        helper.checkChange()

        assertEquals(42L, checkpointStore.saved["chat_logs"])
        assertEquals(null, chatLogRepo.lastPolledAfterLogId)
        helper.close()
    }

    @Test
    fun `accepted command advances persisted checkpoint`() {
        val checkpointStore = FakeCheckpointStore(initial = mapOf("chat_logs" to 10L))
        val chatLogRepo =
            FakeChatLogRepository(
                latestLogId = 10L,
                polledLogs =
                    listOf(
                        webhookChatLogEntry(id = 11L, message = "!ping"),
                    ),
            )
        val helper =
            ObserverHelper(
                db = chatLogRepo,
                config = config,
                checkpointStore = checkpointStore,
                routingGateway = FakeRoutingGateway(result = RoutingResult.ACCEPTED),
            )

        helper.checkChange()
        helper.checkChange()

        assertEquals(11L, checkpointStore.saved["chat_logs"])
        helper.close()
    }

    @Test
    fun `retry later keeps persisted checkpoint unchanged`() {
        val checkpointStore = FakeCheckpointStore(initial = mapOf("chat_logs" to 10L))
        val chatLogRepo =
            FakeChatLogRepository(
                latestLogId = 10L,
                polledLogs =
                    listOf(
                        webhookChatLogEntry(id = 11L, message = "!ping"),
                    ),
            )
        val helper =
            ObserverHelper(
                db = chatLogRepo,
                config = config,
                checkpointStore = checkpointStore,
                routingGateway = FakeRoutingGateway(result = RoutingResult.RETRY_LATER),
            )

        helper.checkChange()
        helper.checkChange()

        assertEquals(10L, checkpointStore.saved["chat_logs"])
        helper.close()
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
            MemberRepository(
                executeQuery = { sqlQuery, bindArgs, _ ->
                    when {
                        sqlQuery == "SELECT name, enc FROM db2.friends WHERE id = ? LIMIT 1" -> emptyList()
                        sqlQuery.contains("FROM db3.observed_profile_user_links") &&
                            bindArgs?.toList() == listOf("203887151", "366795577484293") ->
                            listOf(mapOf("display_name" to "재균"))
                        else -> emptyList()
                    }
                },
                decrypt = { _, raw, _ -> raw },
                botId = config.botId,
            )
        val routingGateway = FakeRoutingGateway(result = RoutingResult.ACCEPTED)
        val helper =
            ObserverHelper(
                db = chatLogRepo,
                config = config,
                memberRepo = memberRepo,
                checkpointStore = checkpointStore,
                routingGateway = routingGateway,
            )

        helper.checkChange()
        helper.checkChange()

        assertEquals("재균", routingGateway.commands.single().sender)
        helper.close()
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
        val helper =
            ObserverHelper(
                db = chatLogRepo,
                config = config,
                checkpointStore = checkpointStore,
                routingGateway = FakeRoutingGateway(result = RoutingResult.ACCEPTED),
                learnFromTimestampCorrelation = { chatId, userId, messageCreatedAtMs ->
                    calls += Triple(chatId, userId, messageCreatedAtMs)
                },
            )

        helper.checkChange()
        helper.checkChange()

        assertEquals(listOf(Triple(123L, 456L, 1_711_111_111_000L)), calls)
        helper.close()
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
        metadata = """{"enc":0,"origin":"CHATLOG"}""",
        createdAt = createdAt,
    )

private fun snapshotSequenceMemberRepository(
    roomList: party.qwer.iris.model.RoomListResponse,
    snapshots: List<RoomSnapshotData>,
): MemberRepository {
    val snapshotQueue = ArrayDeque(snapshots)
    var currentSnapshot: RoomSnapshotData? = null
    return MemberRepository(
        executeQuery = { sqlQuery, _, _ ->
            when {
                sqlQuery.contains("FROM chat_rooms cr") ->
                    roomList.rooms.map { room ->
                        mapOf(
                            "id" to room.chatId.toString(),
                            "type" to room.type,
                            "active_members_count" to room.activeMembersCount?.toString(),
                            "link_id" to room.linkId?.toString(),
                            "link_name" to room.linkName,
                            "link_url" to room.linkUrl,
                            "member_limit" to room.memberLimit?.toString(),
                            "searchable" to room.searchable?.toString(),
                            "bot_role" to room.botRole?.toString(),
                        )
                    }
                sqlQuery == "SELECT members, blinded_member_ids, link_id FROM chat_rooms WHERE id = ?" -> {
                    val snapshot = snapshotQueue.removeFirst()
                    currentSnapshot = snapshot
                    listOf(
                        mapOf(
                            "members" to snapshot.memberIds.joinToString(prefix = "[", postfix = "]"),
                            "blinded_member_ids" to snapshot.blindedIds.joinToString(prefix = "[", postfix = "]"),
                            "link_id" to snapshot.linkId?.toString(),
                        ),
                    )
                }
                sqlQuery == "SELECT user_id, nickname, link_member_type, profile_image_url, enc FROM db2.open_chat_member WHERE link_id = ?" -> {
                    val snapshot = currentSnapshot
                    if (snapshot == null) {
                        emptyList()
                    } else {
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
}
