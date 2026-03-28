package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals

class ObservedProfileUserLinkingTest {
    @Test
    fun `extracts chat id from notification key`() {
        assertEquals(18478615493603057L, extractChatIdFromNotificationKey("0|com.kakao.talk|2|18478615493603057|10078"))
        assertEquals(null, extractChatIdFromNotificationKey("bad-key"))
    }

    @Test
    fun `matches observed profiles to unique known room member names`() {
        val links =
            matchObservedProfileUserLinks(
                chatId = 123L,
                observedProfiles =
                    listOf(
                        ObservedProfileRecord(
                            stableId = "stable-1",
                            displayName = "Alice",
                            roomName = "Room A",
                        ),
                    ),
                userDisplayNames = mapOf(42L to "Alice"),
            )

        assertEquals(
            listOf(
                ObservedProfileUserLink(
                    stableId = "stable-1",
                    userId = 42L,
                    chatId = 123L,
                    displayName = "Alice",
                    roomName = "Room A",
                ),
            ),
            links,
        )
    }

    @Test
    fun `ignores ambiguous display names when learning mappings`() {
        val links =
            matchObservedProfileUserLinks(
                chatId = 123L,
                observedProfiles =
                    listOf(
                        ObservedProfileRecord(
                            stableId = "stable-1",
                            displayName = "Alice",
                            roomName = "Room A",
                        ),
                    ),
                userDisplayNames = mapOf(42L to "Alice", 43L to "Alice"),
            )

        assertEquals(emptyList(), links)
    }
}
