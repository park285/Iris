package party.qwer.iris.ingress

import party.qwer.iris.ChatLogRepository
import party.qwer.iris.KakaoDB
import kotlin.test.Test
import kotlin.test.assertEquals

class SenderNameResolverTest {
    @Test
    fun `canonical open nickname uses injected canonical resolver`() {
        val resolver =
            SenderNameResolver(
                db = StubChatLogRepository(senderName = "fallback"),
                memberRepo = null,
                canonicalOpenNicknameResolver = { _, _ -> "Open Nick" },
            )

        val resolved = resolver.resolveCanonicalOpenNicknameFresh(userId = 200L, linkId = 300L)

        assertEquals("Open Nick", resolved)
    }

    @Test
    fun `canonical open nickname falls back to db name when resolver returns null`() {
        val resolver =
            SenderNameResolver(
                db = StubChatLogRepository(senderName = "fallback"),
                memberRepo = null,
                canonicalOpenNicknameResolver = { _, _ -> null },
            )

        val resolved = resolver.resolveCanonicalOpenNicknameFresh(userId = 200L, linkId = 300L)

        assertEquals("fallback", resolved)
    }

    @Test
    fun `canonical open nickname can recover after delayed availability`() {
        var calls = 0
        val resolver =
            SenderNameResolver(
                db = StubChatLogRepository(senderName = "fallback"),
                memberRepo = null,
                canonicalOpenNicknameResolver = { _, _ ->
                    calls += 1
                    if (calls == 1) null else "Recovered Nick"
                },
            )

        val first = resolver.resolveCanonicalOpenNicknameFresh(userId = 200L, linkId = 300L)
        val second = resolver.resolveCanonicalOpenNicknameFresh(userId = 200L, linkId = 300L)

        assertEquals("fallback", first)
        assertEquals("Recovered Nick", second)
    }

    private class StubChatLogRepository(
        private val senderName: String,
    ) : ChatLogRepository {
        override fun pollChatLogsAfter(
            afterLogId: Long,
            limit: Int,
        ): List<KakaoDB.ChatLogEntry> = emptyList()

        override fun resolveSenderName(userId: Long): String = senderName

        override fun resolveRoomMetadata(chatId: Long): KakaoDB.RoomMetadata = KakaoDB.RoomMetadata()

        override fun latestLogId(): Long = 0L
    }
}
