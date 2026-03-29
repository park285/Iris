package party.qwer.iris

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import party.qwer.iris.http.SseEventEnvelope
import party.qwer.iris.http.SseSubscriberPolicy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class SseEventBus(
    private val policy: SseSubscriberPolicy = SseSubscriberPolicy(),
) {
    constructor(bufferSize: Int) : this(
        SseSubscriberPolicy(bufferCapacity = bufferSize, replayWindowSize = bufferSize),
    )

    private val idCounter = AtomicLong(0)
    private val buffer = ArrayDeque<SseEventEnvelope>(policy.bufferCapacity)
    private val lock = Any()
    private val replayMisses = AtomicLong(0)

    private data class Subscriber(
        val channel: Channel<SseEventEnvelope>,
        @Volatile var markedSlowAtMs: Long = 0L,
    )

    private val subscribers = CopyOnWriteArrayList<Subscriber>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val listeners = CopyOnWriteArrayList<(Long, String) -> Unit>()

    // -- 레거시 API --

    fun emit(data: String) {
        emit(data, "message")
    }

    fun replayFrom(afterId: Long): List<Pair<Long, String>> = replayEnvelopes(afterId).map { it.id to it.payload }

    // -- 신규 API --

    fun emit(
        data: String,
        eventType: String,
    ) {
        val id = idCounter.incrementAndGet()
        val envelope =
            SseEventEnvelope(
                id = id,
                eventType = eventType,
                payload = data,
                createdAtMs = System.currentTimeMillis(),
            )
        synchronized(lock) {
            if (buffer.size >= policy.bufferCapacity) buffer.removeFirst()
            buffer.addLast(envelope)
        }

        for (listener in listeners) {
            try {
                listener(id, data)
            } catch (_: Exception) {
            }
        }

        val iterator = subscribers.iterator()
        while (iterator.hasNext()) {
            val sub = iterator.next()
            val sendResult = sub.channel.trySend(envelope)
            if (sendResult.isFailure) {
                if (sub.markedSlowAtMs == 0L) {
                    sub.markedSlowAtMs = System.currentTimeMillis()
                    scheduleSlowSubscriberEviction(sub)
                }
            } else {
                sub.markedSlowAtMs = 0L
            }
        }
    }

    fun replayEnvelopes(afterId: Long): List<SseEventEnvelope> {
        synchronized(lock) {
            val oldestInBuffer = buffer.firstOrNull()?.id ?: return emptyList()

            if (afterId > 0 && afterId < oldestInBuffer) {
                replayMisses.incrementAndGet()
            }

            val matching = buffer.filter { it.id > afterId }
            return if (matching.size > policy.replayWindowSize) {
                matching.takeLast(policy.replayWindowSize)
            } else {
                matching
            }
        }
    }

    fun replayMissCount(): Long = replayMisses.get()

    fun addSubscriber(channel: Channel<SseEventEnvelope>) {
        subscribers.add(Subscriber(channel))
    }

    fun removeSubscriber(channel: Channel<SseEventEnvelope>) {
        subscribers.removeAll { it.channel === channel }
    }

    fun subscriberCount(): Int = subscribers.size

    private fun scheduleSlowSubscriberEviction(sub: Subscriber) {
        scope.launch {
            delay(policy.slowSubscriberTimeoutMs)
            if (sub.markedSlowAtMs > 0L) {
                subscribers.remove(sub)
                sub.channel.close()
            }
        }
    }
}
