package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KakaoNotificationProfileParserTest {
    @Test
    fun `selects latest sender candidate with meaningful identity`() {
        val selected =
            selectLatestSenderCandidate(
                listOf(
                    MessagingSenderCandidate(
                        stableKey = "user-1",
                        displayName = "Older",
                        timestamp = 100L,
                    ),
                    MessagingSenderCandidate(
                        stableKey = "",
                        displayName = "Ignored",
                        timestamp = 300L,
                    ),
                    MessagingSenderCandidate(
                        stableKey = "user-2",
                        displayName = "Latest",
                        timestamp = 200L,
                    ),
                ),
            )

        assertEquals(
            MessagingSenderCandidate(
                stableKey = "user-2",
                displayName = "Latest",
                timestamp = 200L,
            ),
            selected,
        )
    }

    @Test
    fun `returns null when every sender candidate is empty`() {
        val selected =
            selectLatestSenderCandidate(
                listOf(
                    MessagingSenderCandidate(
                        stableKey = null,
                        displayName = "   ",
                        timestamp = 100L,
                    ),
                ),
            )

        assertNull(selected)
    }

    @Test
    fun `prefers conversation title when resolving room name`() {
        val roomName =
            resolveRoomNameValue(
                conversationTitle = "Open Chat",
                subText = "Sub",
                summaryText = "Summary",
                fallbackTitle = "Title",
                displayName = "Display",
            )

        assertEquals("Open Chat", roomName)
    }

    @Test
    fun `falls back to display name when room fields are absent`() {
        val roomName =
            resolveRoomNameValue(
                conversationTitle = null,
                subText = "  ",
                summaryText = null,
                fallbackTitle = "",
                displayName = "Display",
            )

        assertEquals("Display", roomName)
    }
}
