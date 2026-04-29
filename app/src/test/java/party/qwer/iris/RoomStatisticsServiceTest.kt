package party.qwer.iris

import party.qwer.iris.storage.MessageLogRow
import party.qwer.iris.storage.MessageTypeCountRow
import party.qwer.iris.storage.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomStatisticsServiceTest {
    @Test
    fun `room stats projection preserves totals before limit and stable count ordering`() {
        val projection =
            projectRoomStatsKotlin(
                rows =
                    listOf(
                        MessageTypeCountRow(userId = UserId(1), type = "0", count = 4, lastActive = 100L),
                        MessageTypeCountRow(userId = UserId(2), type = "1", count = 7, lastActive = 90L),
                        MessageTypeCountRow(userId = UserId(1), type = "9", count = 3, lastActive = 120L),
                        MessageTypeCountRow(userId = UserId(3), type = "0", count = 2, lastActive = 80L),
                    ),
                nicknameByUser = mapOf(UserId(1) to "alice", UserId(2) to "bob"),
                messageTypeNames = mapOf("0" to "text", "1" to "image"),
                limit = 1,
                minMessages = 5,
            )

        assertEquals(14, projection.totalMessages)
        assertEquals(2, projection.activeMembers)
        assertEquals(listOf(1L), projection.memberStats.map { it.userId })
        assertEquals("alice", projection.memberStats.single().nickname)
        assertEquals(7, projection.memberStats.single().messageCount)
        assertEquals(120L, projection.memberStats.single().lastActiveAt)
        assertEquals(mapOf("text" to 4, "other" to 3), projection.memberStats.single().messageTypes)
    }

    @Test
    fun `member activity projection skips zero timestamps except message count`() {
        val projection =
            projectMemberActivityKotlin(
                rows =
                    listOf(
                        MessageLogRow(type = "0", createdAt = 0L),
                        MessageLogRow(type = "0", createdAt = 3_600L),
                        MessageLogRow(type = "1", createdAt = 7_200L),
                        MessageLogRow(type = "9", createdAt = 90_000L),
                    ),
                messageTypeNames = mapOf("0" to "text", "1" to "image"),
            )

        assertEquals(4, projection.messageCount)
        assertEquals(3_600L, projection.firstMessageAt)
        assertEquals(90_000L, projection.lastMessageAt)
        assertEquals(2, projection.activeHours[1])
        assertEquals(1, projection.activeHours[2])
        assertEquals(0, projection.activeHours[0])
        assertEquals(mapOf("text" to 1, "image" to 1, "other" to 1), projection.messageTypes)
    }

    @Test
    fun `member activity projection returns empty temporal fields when all timestamps are zero`() {
        val projection =
            projectMemberActivityKotlin(
                rows = listOf(MessageLogRow(type = "0", createdAt = 0L)),
                messageTypeNames = mapOf("0" to "text"),
            )

        assertEquals(1, projection.messageCount)
        assertNull(projection.firstMessageAt)
        assertNull(projection.lastMessageAt)
        assertEquals(List(24) { 0 }, projection.activeHours)
        assertEquals(emptyMap(), projection.messageTypes)
    }
}
