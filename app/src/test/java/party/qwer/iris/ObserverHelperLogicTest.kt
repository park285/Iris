package party.qwer.iris

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `snapshot diff emits member events after prior snapshot exists`() {
        val roomList = party.qwer.iris.model.RoomListResponse(
            rooms = listOf(party.qwer.iris.model.RoomSummary(chatId = 100L, type = "OM", linkId = 200L, activeMembersCount = 2)),
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
        val helper = ObserverHelper(chatLogRepo, config, memberRepo, snapshotManager, bus)

        helper.checkChange()
        val firstReplay = bus.replayFrom(0)

        chatLogRepo.latestLogId = 2L
        chatLogRepo.polledLogs = listOf(
            KakaoDB.ChatLogEntry(
                id = 2L,
                chatId = 100L,
                userId = 9L,
                message = "not a command",
                metadata = "{\"enc\":0,\"origin\":\"CHATLOG\"}",
                createdAt = "2026-03-27T00:00:00Z",
                messageType = "0",
            ),
        )

        helper.checkChange()
        val secondReplay = bus.replayFrom(0)

        chatLogRepo.latestLogId = 3L
        chatLogRepo.polledLogs = listOf(
            KakaoDB.ChatLogEntry(
                id = 3L,
                chatId = 100L,
                userId = 9L,
                message = "still not a command",
                metadata = "{\"enc\":0,\"origin\":\"CHATLOG\"}",
                createdAt = "2026-03-27T00:00:01Z",
                messageType = "0",
            ),
        )

        helper.checkChange()
        val thirdReplay = bus.replayFrom(0)

        assertTrue(firstReplay.isEmpty())
        assertTrue(secondReplay.isEmpty())
        assertEquals(1, thirdReplay.size)
        val eventJson = json.parseToJsonElement(thirdReplay.single().second).jsonObject
        assertEquals("join", eventJson.getValue("event").jsonPrimitive.content)
        assertEquals("2", eventJson.getValue("userId").jsonPrimitive.content)

        helper.close()
    }
}

private class FakeChatLogRepository(
    var latestLogId: Long = 1L,
    var polledLogs: List<KakaoDB.ChatLogEntry> = emptyList(),
) : ChatLogRepository {
    override fun pollChatLogsAfter(afterLogId: Long, limit: Int): List<KakaoDB.ChatLogEntry> = polledLogs

    override fun resolveSenderName(userId: Long): String = userId.toString()

    override fun resolveRoomMetadata(chatId: Long): KakaoDB.RoomMetadata = KakaoDB.RoomMetadata()

    override fun latestLogId(): Long = latestLogId

    override fun executeQuery(
        sqlQuery: String,
        bindArgs: Array<String?>?,
        maxRows: Int,
    ): List<Map<String, String?>> = emptyList()
}

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
