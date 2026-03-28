package party.qwer.iris

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class SseEventBus(
    private val bufferSize: Int = 100,
) {
    private val idCounter = AtomicLong(0)
    private val buffer = ArrayDeque<Pair<Long, String>>(bufferSize)
    private val lock = Any()

    /** 실시간으로 (id, data) 쌍을 수신하는 리스너 목록. */
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
                // SSE 리스너 실패가 운영 경로에 영향을 주면 안 됨
            }
        }
    }

    /** 링 버퍼에서 afterId 이후의 이벤트를 반환한다. */
    fun replayFrom(afterId: Long): List<Pair<Long, String>> {
        synchronized(lock) {
            return buffer.filter { it.first > afterId }
        }
    }
}
