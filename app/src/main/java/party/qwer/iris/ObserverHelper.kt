package party.qwer.iris

import kotlinx.coroutines.runBlocking
import party.qwer.iris.ingress.CommandIngressService
import party.qwer.iris.persistence.CheckpointJournal
import party.qwer.iris.snapshot.SnapshotCommand
import party.qwer.iris.snapshot.SnapshotCoordinator
import java.io.Closeable

class ObserverHelper(
    private val ingressService: CommandIngressService,
    private val snapshotCoordinator: SnapshotCoordinator,
    private val checkpointJournal: CheckpointJournal,
) : Closeable {
    fun checkChange() {
        ingressService.checkChange()
    }

    fun seedSnapshotCache() {
        runBlocking {
            snapshotCoordinator.send(SnapshotCommand.SeedCache)
        }
    }

    internal fun markRoomDirty(chatId: Long) {
        runBlocking {
            snapshotCoordinator.send(SnapshotCommand.MarkDirty(chatId))
        }
    }

    internal fun markAllRoomsDirty() {
        runBlocking {
            snapshotCoordinator.send(SnapshotCommand.FullReconcile)
        }
    }

    internal fun dirtyRoomCount(): Int = snapshotCoordinator.dirtyRoomCount()

    internal fun runDirtySnapshotDiff(maxRoomsPerTick: Int = 32) {
        runBlocking {
            snapshotCoordinator.send(SnapshotCommand.Drain(maxRoomsPerTick))
        }
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
