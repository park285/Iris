package party.qwer.iris.snapshot

import party.qwer.iris.storage.ChatId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SnapshotCommandTest {
    @Test
    fun `MarkDirty carries chatId`() {
        val cmd = SnapshotCommand.MarkDirty(chatId = ChatId(100L))
        assertIs<SnapshotCommand>(cmd)
        assertTrue(cmd.chatId == ChatId(100L))
    }

    @Test
    fun `MarkDirty equality uses chatId`() {
        assertEquals(
            SnapshotCommand.MarkDirty(chatId = ChatId(100L)),
            SnapshotCommand.MarkDirty(chatId = ChatId(100L)),
        )
        assertNotEquals(
            SnapshotCommand.MarkDirty(chatId = ChatId(100L)),
            SnapshotCommand.MarkDirty(chatId = ChatId(200L)),
        )
    }

    @Test
    fun `Drain carries budget`() {
        val cmd = SnapshotCommand.Drain(budget = 64)
        assertIs<SnapshotCommand>(cmd)
        assertEquals(64, cmd.budget)
    }

    @Test
    fun `FullReconcile is singleton`() {
        assertIs<SnapshotCommand>(SnapshotCommand.FullReconcile)
    }

    @Test
    fun `SeedCache is singleton`() {
        assertIs<SnapshotCommand>(SnapshotCommand.SeedCache)
    }

    @Test
    fun `all variants are SnapshotCommand subtypes`() {
        val commands: List<SnapshotCommand> =
            listOf(
                SnapshotCommand.MarkDirty(chatId = ChatId(1L)),
                SnapshotCommand.Drain(budget = 32),
                SnapshotCommand.FullReconcile,
                SnapshotCommand.SeedCache,
            )
        assertEquals(4, commands.size)
    }
}
