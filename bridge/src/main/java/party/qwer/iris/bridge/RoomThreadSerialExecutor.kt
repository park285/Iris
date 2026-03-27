package party.qwer.iris.bridge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class RoomThreadKey(
    val roomId: Long,
    val threadId: Long?,
)

internal class RoomThreadSerialExecutor {
    private val locks = ConcurrentHashMap<RoomThreadKey, ReentrantLock>()

    fun <T> execute(
        roomId: Long,
        threadId: Long?,
        block: () -> T,
    ): T {
        val lock = locks.computeIfAbsent(RoomThreadKey(roomId, threadId)) { ReentrantLock() }
        return lock.withLock(block)
    }
}
