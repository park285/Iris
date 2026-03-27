package party.qwer.iris

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class SseEventBus(
    private val bufferSize: Int = 100,
) {
    private val idCounter = AtomicLong(0)
    private val buffer = ArrayDeque<Pair<Long, String>>(bufferSize)
    private val lock = Any()

    /** Listeners that receive (id, data) pairs in real-time. */
    val listeners = CopyOnWriteArrayList<(Long, String) -> Unit>()

    fun emit(data: String) {
        val id = idCounter.incrementAndGet()
        synchronized(lock) {
            if (buffer.size >= bufferSize) buffer.removeFirst()
            buffer.addLast(id to data)
        }
        for (listener in listeners) {
            try {
                listener(id, data)
            } catch (_: Exception) {
                // SSE listener failure must not affect operational path
            }
        }
    }

    /** Return events with id > afterId from the ring buffer. */
    fun replayFrom(afterId: Long): List<Pair<Long, String>> {
        synchronized(lock) {
            return buffer.filter { it.first > afterId }
        }
    }
}
