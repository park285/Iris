package party.qwer.iris.imagebridge.runtime

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class RoomThreadKey(
    val roomId: Long,
    val threadId: Long?,
)

internal class RoomThreadSerialExecutor(
    stripeCount: Int = DEFAULT_STRIPE_COUNT,
) {
    private val locks = Array(stripeCount.coerceAtLeast(1)) { ReentrantLock() }

    fun <T> execute(
        roomId: Long,
        threadId: Long?,
        block: () -> T,
    ): T {
        val lock = locks[lockIndex(roomId, threadId)]
        return lock.withLock(block)
    }

    internal fun lockCountForTest(): Int = locks.size

    private fun lockIndex(
        roomId: Long,
        threadId: Long?,
    ): Int = ((31 * roomId.hashCode()) + (threadId?.hashCode() ?: 0)).mod(locks.size)

    private companion object {
        private const val DEFAULT_STRIPE_COUNT = 128
    }
}
