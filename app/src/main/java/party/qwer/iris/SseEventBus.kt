package party.qwer.iris

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import party.qwer.iris.http.SseEventEnvelope
import party.qwer.iris.http.SseSubscriberPolicy
import party.qwer.iris.persistence.SseEventStore
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

private const val SSE_COMMAND_CHANNEL_CAPACITY = 128

internal data class SubscriberReplay(
    val replay: List<SseEventEnvelope>,
    val channel: Channel<SseEventEnvelope>,
)

class SseEventBus(
    private val policy: SseSubscriberPolicy = SseSubscriberPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val store: SseEventStore? = null,
) : Closeable {
    constructor(bufferSize: Int) : this(
        SseSubscriberPolicy(bufferCapacity = bufferSize, replayWindowSize = bufferSize),
    )

    private data class SubscriberState(
        val channel: Channel<SseEventEnvelope>,
        val markedSlowAtMs: Long? = null,
    )

    private sealed interface SseCommand {
        data class Emit(
            val data: String,
            val eventType: String,
            val reply: CompletableDeferred<Unit>,
        ) : SseCommand

        data class Replay(
            val afterId: Long,
            val reply: CompletableDeferred<List<SseEventEnvelope>>,
        ) : SseCommand

        data class OpenSubscriberWithReplay(
            val afterId: Long,
            val reply: CompletableDeferred<SubscriberReplay>,
        ) : SseCommand

        data class ReplayMissCount(
            val reply: CompletableDeferred<Long>,
        ) : SseCommand

        data class OpenSubscriber(
            val reply: CompletableDeferred<Channel<SseEventEnvelope>>,
        ) : SseCommand

        data class AddSubscriber(
            val channel: Channel<SseEventEnvelope>,
            val reply: CompletableDeferred<Unit>,
        ) : SseCommand

        data class RemoveSubscriber(
            val channel: Channel<SseEventEnvelope>,
            val reply: CompletableDeferred<Unit>,
        ) : SseCommand

        data class SubscriberCount(
            val reply: CompletableDeferred<Int>,
        ) : SseCommand

        data class AddListener(
            val listener: (SseEventEnvelope) -> Unit,
            val reply: CompletableDeferred<Long>,
        ) : SseCommand

        data class RemoveListener(
            val listenerId: Long,
            val reply: CompletableDeferred<Unit>,
        ) : SseCommand

        data class EvictSlowSubscriber(
            val channel: Channel<SseEventEnvelope>,
            val markedSlowAtMs: Long,
        ) : SseCommand

        data class Close(
            val reply: CompletableDeferred<Unit>,
        ) : SseCommand
    }

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + dispatcher)
    private val commands = Channel<SseCommand>(SSE_COMMAND_CHANNEL_CAPACITY)
    private val closed = AtomicBoolean(false)
    private val actorJob =
        scope.launch {
            runActor()
        }

    fun emit(data: String) {
        emit(data, "message")
    }

    fun replayFrom(afterId: Long): List<Pair<Long, String>> = replayEnvelopes(afterId).map { it.id to it.payload }

    fun addListener(listener: (SseEventEnvelope) -> Unit): Long =
        if (closed.get()) {
            -1L
        } else {
            runBlocking { addListenerSuspend(listener) }
        }

    suspend fun addListenerSuspend(listener: (SseEventEnvelope) -> Unit): Long =
        if (closed.get()) {
            -1L
        } else {
            dispatchSuspend<SseCommand.AddListener, Long> { reply ->
                SseCommand.AddListener(listener, reply)
            }
        }

    fun removeListener(listenerId: Long) {
        if (closed.get()) return
        runBlocking { removeListenerSuspend(listenerId) }
    }

    suspend fun removeListenerSuspend(listenerId: Long) {
        if (closed.get()) return
        dispatchSuspend<SseCommand.RemoveListener, Unit> { reply ->
            SseCommand.RemoveListener(listenerId, reply)
        }
    }

    fun emit(
        data: String,
        eventType: String,
    ) {
        if (closed.get()) return
        runBlocking { emitSuspend(data, eventType) }
    }

    suspend fun emitSuspend(
        data: String,
        eventType: String,
    ) {
        if (closed.get()) return
        dispatchSuspend<SseCommand.Emit, Unit> { reply -> SseCommand.Emit(data, eventType, reply) }
    }

    fun replayEnvelopes(afterId: Long): List<SseEventEnvelope> =
        if (closed.get()) {
            emptyList()
        } else {
            runBlocking { replayEnvelopesSuspend(afterId) }
        }

    suspend fun replayEnvelopesSuspend(afterId: Long): List<SseEventEnvelope> =
        if (closed.get()) {
            emptyList()
        } else {
            dispatchSuspend<SseCommand.Replay, List<SseEventEnvelope>> { reply ->
                SseCommand.Replay(afterId, reply)
            }
        }

    internal suspend fun openSubscriberWithReplaySuspend(afterId: Long): SubscriberReplay =
        if (closed.get()) {
            SubscriberReplay(emptyList(), closedSubscriberChannel())
        } else {
            dispatchSuspend<SseCommand.OpenSubscriberWithReplay, SubscriberReplay> { reply ->
                SseCommand.OpenSubscriberWithReplay(afterId, reply)
            }
        }

    fun replayMissCount(): Long =
        if (closed.get()) {
            0L
        } else {
            runBlocking { replayMissCountSuspend() }
        }

    suspend fun replayMissCountSuspend(): Long =
        if (closed.get()) {
            0L
        } else {
            dispatchSuspend<SseCommand.ReplayMissCount, Long> { reply ->
                SseCommand.ReplayMissCount(reply)
            }
        }

    fun openSubscriberChannel(): Channel<SseEventEnvelope> =
        if (closed.get()) {
            closedSubscriberChannel()
        } else {
            runBlocking { openSubscriberChannelSuspend() }
        }

    suspend fun openSubscriberChannelSuspend(): Channel<SseEventEnvelope> =
        if (closed.get()) {
            closedSubscriberChannel()
        } else {
            dispatchSuspend<SseCommand.OpenSubscriber, Channel<SseEventEnvelope>> { reply ->
                SseCommand.OpenSubscriber(reply)
            }
        }

    fun addSubscriber(channel: Channel<SseEventEnvelope>) {
        if (closed.get()) {
            channel.close()
            return
        }
        runBlocking { addSubscriberSuspend(channel) }
    }

    suspend fun addSubscriberSuspend(channel: Channel<SseEventEnvelope>) {
        if (closed.get()) {
            channel.close()
            return
        }
        dispatchSuspend<SseCommand.AddSubscriber, Unit> { reply ->
            SseCommand.AddSubscriber(channel, reply)
        }
    }

    fun removeSubscriber(channel: Channel<SseEventEnvelope>) {
        if (closed.get()) return
        runBlocking { removeSubscriberSuspend(channel) }
    }

    suspend fun removeSubscriberSuspend(channel: Channel<SseEventEnvelope>) {
        if (closed.get()) return
        dispatchSuspend<SseCommand.RemoveSubscriber, Unit> { reply ->
            SseCommand.RemoveSubscriber(channel, reply)
        }
    }

    fun subscriberCount(): Int =
        if (closed.get()) {
            0
        } else {
            runBlocking { subscriberCountSuspend() }
        }

    suspend fun subscriberCountSuspend(): Int =
        if (closed.get()) {
            0
        } else {
            dispatchSuspend<SseCommand.SubscriberCount, Int> { reply ->
                SseCommand.SubscriberCount(reply)
            }
        }

    override fun close() {
        runBlocking { closeSuspend() }
    }

    suspend fun closeSuspend() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        dispatchSuspend<SseCommand.Close, Unit> { reply ->
            SseCommand.Close(reply)
        }
        commands.close()
        actorJob.cancelAndJoin()
    }

    private suspend fun runActor() {
        var nextEventId =
            runCatching { store?.maxId() ?: 0L }
                .getOrElse { error ->
                    IrisLogger.error("[SseEventBus] failed to initialize maxId: ${error.message}", error)
                    0L
                }
        var nextListenerId = 0L
        val buffer = ArrayDeque<SseEventEnvelope>(policy.bufferCapacity)
        var replayMisses = 0L
        val subscribers = linkedMapOf<Channel<SseEventEnvelope>, SubscriberState>()
        val listeners = linkedMapOf<Long, (SseEventEnvelope) -> Unit>()

        fun replayAfter(afterId: Long): List<SseEventEnvelope> =
            if (store != null) {
                store.replayAfter(afterId, policy.replayWindowSize)
            } else {
                val oldestInBuffer = buffer.firstOrNull()?.id
                if (oldestInBuffer != null && afterId > 0 && afterId < oldestInBuffer) {
                    replayMisses += 1
                }
                val matching = buffer.filter { it.id > afterId }
                if (matching.size > policy.replayWindowSize) {
                    matching.takeLast(policy.replayWindowSize)
                } else {
                    matching
                }
            }

        for (command in commands) {
            try {
                when (command) {
                    is SseCommand.Emit -> {
                        val createdAtMs = clock()
                        val persistedId = store?.insert(command.eventType, command.data, createdAtMs)
                        nextEventId = persistedId ?: (nextEventId + 1)
                        val envelope =
                            SseEventEnvelope(
                                id = nextEventId,
                                eventType = command.eventType,
                                payload = command.data,
                                createdAtMs = createdAtMs,
                            )
                        if (buffer.size >= policy.bufferCapacity) {
                            buffer.removeFirst()
                        }
                        buffer.addLast(envelope)

                        for (listener in listeners.values) {
                            try {
                                listener(envelope)
                            } catch (_: Exception) {
                            }
                        }

                        subscribers.values.toList().forEach { subscriber ->
                            val sendResult = subscriber.channel.trySend(envelope)
                            if (sendResult.isFailure) {
                                if (subscriber.markedSlowAtMs == null) {
                                    val markedAtMs = clock()
                                    subscribers[subscriber.channel] =
                                        subscriber.copy(markedSlowAtMs = markedAtMs)
                                    scheduleSlowSubscriberEviction(subscriber.channel, markedAtMs)
                                }
                            } else if (subscriber.markedSlowAtMs != null) {
                                subscribers[subscriber.channel] = subscriber.copy(markedSlowAtMs = null)
                            }
                        }

                        command.reply.complete(Unit)
                    }

                    is SseCommand.Replay -> command.reply.complete(replayAfter(command.afterId))

                    is SseCommand.OpenSubscriberWithReplay -> {
                        val channel = Channel<SseEventEnvelope>(policy.bufferCapacity)
                        val replay = replayAfter(command.afterId)
                        subscribers[channel] = SubscriberState(channel)
                        command.reply.complete(SubscriberReplay(replay = replay, channel = channel))
                    }

                    is SseCommand.ReplayMissCount -> command.reply.complete(replayMisses)

                    is SseCommand.OpenSubscriber -> {
                        val channel = Channel<SseEventEnvelope>(policy.bufferCapacity)
                        subscribers[channel] = SubscriberState(channel)
                        command.reply.complete(channel)
                    }

                    is SseCommand.AddSubscriber -> {
                        subscribers[command.channel] = SubscriberState(command.channel)
                        command.reply.complete(Unit)
                    }

                    is SseCommand.RemoveSubscriber -> {
                        subscribers.remove(command.channel)
                        command.reply.complete(Unit)
                    }

                    is SseCommand.SubscriberCount -> command.reply.complete(subscribers.size)

                    is SseCommand.AddListener -> {
                        nextListenerId += 1
                        listeners[nextListenerId] = command.listener
                        command.reply.complete(nextListenerId)
                    }

                    is SseCommand.RemoveListener -> {
                        listeners.remove(command.listenerId)
                        command.reply.complete(Unit)
                    }

                    is SseCommand.EvictSlowSubscriber -> {
                        val current = subscribers[command.channel] ?: continue
                        if (current.markedSlowAtMs == command.markedSlowAtMs) {
                            subscribers.remove(command.channel)
                            current.channel.close()
                        }
                    }

                    is SseCommand.Close -> {
                        subscribers.values.forEach { it.channel.close() }
                        subscribers.clear()
                        listeners.clear()
                        command.reply.complete(Unit)
                        break
                    }
                }
            } catch (error: Throwable) {
                IrisLogger.error("[SseEventBus] actor command failed: ${error.message}", error)
                failCommand(command, error)
            }
        }
    }

    private fun scheduleSlowSubscriberEviction(
        channel: Channel<SseEventEnvelope>,
        markedSlowAtMs: Long,
    ) {
        scope.launch {
            delay(policy.slowSubscriberTimeoutMs)
            runCatching {
                commands.send(SseCommand.EvictSlowSubscriber(channel, markedSlowAtMs))
            }
        }
    }

    private suspend fun <C : SseCommand, T> dispatchSuspend(build: (CompletableDeferred<T>) -> C): T {
        val reply = CompletableDeferred<T>()
        val command = build(reply)

        if (!actorJob.isActive) {
            failCommand(command, IllegalStateException("SSE actor unavailable"))
            return reply.await()
        }

        val sendResult = commands.trySend(command)
        if (sendResult.isFailure) {
            val error = sendResult.exceptionOrNull()
            if (error != null || !actorJob.isActive) {
                failCommand(command, error ?: IllegalStateException("SSE actor unavailable"))
            } else {
                runCatching { commands.send(command) }
                    .onFailure { sendError -> failCommand(command, sendError) }
            }
        }

        return reply.await()
    }

    private fun failCommand(
        command: SseCommand,
        error: Throwable,
    ) {
        when (command) {
            is SseCommand.Emit -> command.reply.completeExceptionally(error)
            is SseCommand.Replay -> command.reply.complete(emptyList())
            is SseCommand.OpenSubscriberWithReplay ->
                command.reply.complete(
                    SubscriberReplay(
                        replay = emptyList(),
                        channel = closedSubscriberChannel(),
                    ),
                )
            is SseCommand.ReplayMissCount -> command.reply.complete(0L)
            is SseCommand.OpenSubscriber -> command.reply.complete(closedSubscriberChannel())
            is SseCommand.AddSubscriber -> {
                command.channel.close()
                command.reply.complete(Unit)
            }
            is SseCommand.RemoveSubscriber -> command.reply.complete(Unit)
            is SseCommand.SubscriberCount -> command.reply.complete(0)
            is SseCommand.AddListener -> command.reply.complete(-1L)
            is SseCommand.RemoveListener -> command.reply.complete(Unit)
            is SseCommand.EvictSlowSubscriber -> command.channel.close()
            is SseCommand.Close -> command.reply.complete(Unit)
        }
    }

    private fun closedSubscriberChannel(): Channel<SseEventEnvelope> =
        Channel<SseEventEnvelope>(0).also { it.close() }
}
