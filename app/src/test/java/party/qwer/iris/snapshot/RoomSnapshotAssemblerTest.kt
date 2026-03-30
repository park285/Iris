package party.qwer.iris.snapshot

import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.OpenMemberRow
import party.qwer.iris.storage.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomSnapshotAssemblerTest {
    @Test
    fun `assemble builds snapshot from open member rows`() {
        val openMembers =
            listOf(
                OpenMemberRow(userId = UserId(1L), nickname = "Alice", linkMemberType = 1, profileImageUrl = "http://a", enc = 0),
                OpenMemberRow(userId = UserId(2L), nickname = "Bob", linkMemberType = 2, profileImageUrl = null, enc = 0),
            )
        val batchNicknames = mapOf(1L to "Alice", 2L to "Bob", 3L to "Charlie")

        val snapshot =
            RoomSnapshotAssembler.assemble(
                chatId = ChatId(42L),
                linkId = LinkId(10L),
                memberIds = setOf(UserId(1L), UserId(2L), UserId(3L)),
                blindedIds = setOf(UserId(99L)),
                openMembers = openMembers,
                batchNicknames = batchNicknames.mapKeys { UserId(it.key) },
                decrypt = { _, s, _ -> s },
                botId = UserId(1L),
            )

        assertEquals(ChatId(42L), snapshot.chatId)
        assertEquals(LinkId(10L), snapshot.linkId)
        assertEquals(setOf(UserId(1L), UserId(2L), UserId(3L)), snapshot.memberIds)
        assertEquals(setOf(UserId(99L)), snapshot.blindedIds)
        assertEquals("Alice", snapshot.nicknames[UserId(1L)])
        assertEquals("Bob", snapshot.nicknames[UserId(2L)])
        assertEquals("Charlie", snapshot.nicknames[UserId(3L)])
        assertEquals(1, snapshot.roles[UserId(1L)])
        assertEquals(2, snapshot.roles[UserId(2L)])
        assertEquals("http://a", snapshot.profileImages[UserId(1L)])
        assertTrue(UserId(2L) !in snapshot.profileImages)
    }

    @Test
    fun `assemble decrypts nicknames when enc is positive`() {
        val openMembers =
            listOf(
                OpenMemberRow(userId = UserId(1L), nickname = "encrypted", linkMemberType = 2, profileImageUrl = null, enc = 3),
            )
        val decrypt: (Int, String, Long) -> String = { enc, raw, _ -> "dec($enc:$raw)" }

        val snapshot =
            RoomSnapshotAssembler.assemble(
                chatId = ChatId(42L),
                linkId = LinkId(10L),
                memberIds = setOf(UserId(1L)),
                blindedIds = emptySet(),
                openMembers = openMembers,
                batchNicknames = mapOf(UserId(1L) to "dec(3:encrypted)"),
                decrypt = decrypt,
                botId = UserId(1L),
            )

        assertEquals("dec(3:encrypted)", snapshot.nicknames[UserId(1L)])
    }

    @Test
    fun `assemble without open members uses batch nicknames only`() {
        val snapshot =
            RoomSnapshotAssembler.assemble(
                chatId = ChatId(42L),
                linkId = null,
                memberIds = setOf(UserId(1L), UserId(2L)),
                blindedIds = emptySet(),
                openMembers = emptyList(),
                batchNicknames = mapOf(UserId(1L) to "Alice", UserId(2L) to "Bob"),
                decrypt = { _, s, _ -> s },
                botId = UserId(1L),
            )

        assertEquals("Alice", snapshot.nicknames[UserId(1L)])
        assertEquals("Bob", snapshot.nicknames[UserId(2L)])
        assertTrue(snapshot.roles.isEmpty())
        assertTrue(snapshot.profileImages.isEmpty())
    }

    @Test
    fun `assemble skips string-userId nicknames from batch`() {
        val snapshot =
            RoomSnapshotAssembler.assemble(
                chatId = ChatId(42L),
                linkId = null,
                memberIds = setOf(UserId(1L), UserId(2L)),
                blindedIds = emptySet(),
                openMembers = emptyList(),
                batchNicknames = mapOf(UserId(1L) to "Alice", UserId(2L) to "2"),
                decrypt = { _, s, _ -> s },
                botId = UserId(1L),
            )

        assertEquals("Alice", snapshot.nicknames[UserId(1L)])
        assertTrue(UserId(2L) !in snapshot.nicknames)
    }
}
