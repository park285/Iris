package party.qwer.iris

import party.qwer.iris.model.ReplyStatusSnapshot
import java.time.Duration
import java.util.LinkedHashMap

internal class ReplyStatusStore(
    private val maximumSize: Int = 10_000,
    expireAfterWrite: Duration = Duration.ofMinutes(30),
    private val tickerNanos: () -> Long = System::nanoTime,
) {
    private data class TimedSnapshot(
        val snapshot: ReplyStatusSnapshot,
        val writtenAtNanos: Long,
    )

    private val expireAfterWriteNanos = expireAfterWrite.toNanos()
    private val snapshots = LinkedHashMap<String, TimedSnapshot>()
    private val lock = Any()

    fun update(
        requestId: String,
        state: String,
        detail: String? = null,
    ) {
        synchronized(lock) {
            val now = tickerNanos()
            evictExpiredLocked(now)
            snapshots.remove(requestId)
            snapshots[requestId] =
                TimedSnapshot(
                    snapshot =
                        ReplyStatusSnapshot(
                            requestId = requestId,
                            state = state,
                            updatedAtEpochMs = System.currentTimeMillis(),
                            detail = detail,
                        ),
                    writtenAtNanos = now,
                )
            trimToMaximumSizeLocked()
        }
    }

    fun get(requestId: String): ReplyStatusSnapshot? =
        synchronized(lock) {
            evictExpiredLocked(tickerNanos())
            snapshots[requestId]?.snapshot
        }

    internal fun sizeForTest(): Int =
        synchronized(lock) {
            evictExpiredLocked(tickerNanos())
            snapshots.size
        }

    private fun evictExpiredLocked(nowNanos: Long) {
        val iterator = snapshots.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowNanos - entry.value.writtenAtNanos >= expireAfterWriteNanos) {
                iterator.remove()
            }
        }
    }

    private fun trimToMaximumSizeLocked() {
        while (snapshots.size > maximumSize) {
            val iterator = snapshots.entries.iterator()
            if (!iterator.hasNext()) {
                return
            }
            iterator.next()
            iterator.remove()
        }
    }
}
