package party.qwer.iris.snapshot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class SnapshotCommandTest {
    @Test
    fun `MarkDirty carries chatId`() {
        val cmd = SnapshotCommand.MarkDirty(chatId = 100L)
        assertIs<SnapshotCommand>(cmd)
        assertEquals(100L, cmd.chatId)
    }

    @Test
    fun `MarkDirty equality uses chatId`() {
        assertEquals(
            SnapshotCommand.MarkDirty(chatId = 100L),
            SnapshotCommand.MarkDirty(chatId = 100L),
        )
        assertNotEquals(
            SnapshotCommand.MarkDirty(chatId = 100L),
            SnapshotCommand.MarkDirty(chatId = 200L),
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
                SnapshotCommand.MarkDirty(chatId = 1L),
                SnapshotCommand.Drain(budget = 32),
                SnapshotCommand.FullReconcile,
                SnapshotCommand.SeedCache,
            )
        assertEquals(4, commands.size)
    }
}
