package party.qwer.iris.snapshot

import party.qwer.iris.storage.OpenMemberRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomSnapshotAssemblerTest {
    @Test
    fun `assemble builds snapshot from open member rows`() {
        val openMembers =
            listOf(
                OpenMemberRow(userId = 1L, nickname = "Alice", linkMemberType = 1, profileImageUrl = "http://a", enc = 0),
                OpenMemberRow(userId = 2L, nickname = "Bob", linkMemberType = 2, profileImageUrl = null, enc = 0),
            )
        val batchNicknames = mapOf(1L to "Alice", 2L to "Bob", 3L to "Charlie")

        val snapshot =
            RoomSnapshotAssembler.assemble(
                chatId = 42L,
                linkId = 10L,
                memberIds = setOf(1L, 2L, 3L),
                blindedIds = setOf(99L),
                openMembers = openMembers,
                batchNicknames = batchNicknames,
                decrypt = { _, s, _ -> s },
                botId = 1L,
            )

        assertEquals(42L, snapshot.chatId)
        assertEquals(10L, snapshot.linkId)
        assertEquals(setOf(1L, 2L, 3L), snapshot.memberIds)
        assertEquals(setOf(99L), snapshot.blindedIds)
        assertEquals("Alice", snapshot.nicknames[1L])
        assertEquals("Bob", snapshot.nicknames[2L])
        assertEquals("Charlie", snapshot.nicknames[3L])
        assertEquals(1, snapshot.roles[1L])
        assertEquals(2, snapshot.roles[2L])
        assertEquals("http://a", snapshot.profileImages[1L])
        assertTrue(2L !in snapshot.profileImages)
    }

    @Test
    fun `assemble decrypts nicknames when enc is positive`() {
        val openMembers =
            listOf(
                OpenMemberRow(userId = 1L, nickname = "encrypted", linkMemberType = 2, profileImageUrl = null, enc = 3),
            )
        val decrypt: (Int, String, Long) -> String = { enc, raw, _ -> "dec($enc:$raw)" }

        val snapshot =
            RoomSnapshotAssembler.assemble(
                chatId = 42L,
                linkId = 10L,
                memberIds = setOf(1L),
                blindedIds = emptySet(),
                openMembers = openMembers,
                batchNicknames = mapOf(1L to "dec(3:encrypted)"),
                decrypt = decrypt,
                botId = 1L,
            )

        assertEquals("dec(3:encrypted)", snapshot.nicknames[1L])
    }

    @Test
    fun `assemble without open members uses batch nicknames only`() {
        val snapshot =
            RoomSnapshotAssembler.assemble(
                chatId = 42L,
                linkId = null,
                memberIds = setOf(1L, 2L),
                blindedIds = emptySet(),
                openMembers = emptyList(),
                batchNicknames = mapOf(1L to "Alice", 2L to "Bob"),
                decrypt = { _, s, _ -> s },
                botId = 1L,
            )

        assertEquals("Alice", snapshot.nicknames[1L])
        assertEquals("Bob", snapshot.nicknames[2L])
        assertTrue(snapshot.roles.isEmpty())
        assertTrue(snapshot.profileImages.isEmpty())
    }

    @Test
    fun `assemble skips string-userId nicknames from batch`() {
        val snapshot =
            RoomSnapshotAssembler.assemble(
                chatId = 42L,
                linkId = null,
                memberIds = setOf(1L, 2L),
                blindedIds = emptySet(),
                openMembers = emptyList(),
                batchNicknames = mapOf(1L to "Alice", 2L to "2"),
                decrypt = { _, s, _ -> s },
                botId = 1L,
            )

        assertEquals("Alice", snapshot.nicknames[1L])
        assertTrue(2L !in snapshot.nicknames)
    }
}
