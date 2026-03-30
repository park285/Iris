package party.qwer.iris

import party.qwer.iris.ingress.CommandIngressService
import party.qwer.iris.persistence.CheckpointJournal
import party.qwer.iris.snapshot.SnapshotCommand
import party.qwer.iris.snapshot.SnapshotCoordinator
import party.qwer.iris.storage.ChatId
import java.io.Closeable

internal class ObserverHelper(
    private val ingressService: CommandIngressService,
    private val snapshotCoordinator: SnapshotCoordinator,
    private val checkpointJournal: CheckpointJournal,
) : Closeable {
    fun checkChange() {
        ingressService.checkChange()
    }

    fun seedSnapshotCache() {
        snapshotCoordinator.enqueue(SnapshotCommand.SeedCache)
    }

    internal fun markRoomDirty(chatId: Long) {
        snapshotCoordinator.enqueue(SnapshotCommand.MarkDirty(ChatId(chatId)))
    }

    internal fun markAllRoomsDirty() {
        snapshotCoordinator.enqueue(SnapshotCommand.FullReconcile)
    }

    internal fun dirtyRoomCount(): Int = snapshotCoordinator.dirtyRoomCount()

    internal fun runDirtySnapshotDiff(maxRoomsPerTick: Int = 32) {
        snapshotCoordinator.enqueue(SnapshotCommand.Drain(maxRoomsPerTick))
    }

    override fun close() {
        checkpointJournal.flushNow()
        ingressService.close()
    }
}

internal fun shouldSkipOrigin(
    origin: String?,
    parsedCommand: ParsedCommand,
): Boolean = (origin == "SYNCMSG" || origin == "MCHATLOGS") && parsedCommand.kind == CommandKind.NONE

internal fun isOwnBotMessage(
    userId: Long,
    botId: Long,
): Boolean = botId != 0L && userId == botId

internal fun String.stableLogHash(): String = Integer.toHexString(hashCode())
