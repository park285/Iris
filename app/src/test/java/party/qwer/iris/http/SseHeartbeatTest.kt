package party.qwer.iris.http

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import party.qwer.iris.SseEventBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SSE keepalive heartbeat 및 initialSseFrames 포맷 검증 테스트.
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class SseHeartbeatTest {
    // ── initialSseFrames 포맷 검증 ──────────────────────────────────────────

    @Test
    fun `initialSseFrames empty replay produces only connected comment`() {
        // 재연 이벤트가 없을 때 `: connected\n\n` 만 출력되어야 한다.
        val result = initialSseFrames(emptyList())

        assertEquals(": connected\n\n", result)
    }

    @Test
    fun `initialSseFrames single event includes all SSE fields`() {
        val envelope = SseEventEnvelope(id = 7, eventType = "member_event", payload = """{"a":1}""", createdAtMs = 0)

        val result = initialSseFrames(listOf(envelope))

        assertTrue(result.startsWith(": connected\n\n"), "connected 코멘트가 맨 앞에 있어야 한다")
        assertTrue(result.contains("id: 7\n"), "id 필드 포함")
        assertTrue(result.contains("event: member_event\n"), "event 필드 포함")
        assertTrue(result.contains("""data: {"a":1}"""), "data 필드 포함")
    }

    @Test
    fun `initialSseFrames splits multiline payload into multiple data lines`() {
        val envelope = SseEventEnvelope(id = 7, eventType = "member_event", payload = "line1\nline2", createdAtMs = 0)

        val result = initialSseFrames(listOf(envelope))

        assertTrue(result.contains("data: line1\ndata: line2\n\n"))
    }

    @Test
    fun `initialSseFrames sanitizes event type newlines`() {
        val envelope = SseEventEnvelope(id = 7, eventType = "member\nevent", payload = """{"a":1}""", createdAtMs = 0)

        val result = initialSseFrames(listOf(envelope))

        assertTrue(result.contains("event: member event\n"))
        assertFalse(result.contains("event: member\nevent\n"))
    }

    @Test
    fun `initialSseFrames multiple events appear in order after connected`() {
        val envelopes =
            listOf(
                SseEventEnvelope(id = 1, eventType = "snapshot", payload = "A", createdAtMs = 0),
                SseEventEnvelope(id = 2, eventType = "snapshot", payload = "B", createdAtMs = 0),
            )

        val result = initialSseFrames(envelopes)

        val connectedIdx = result.indexOf(": connected\n\n")
        val firstIdx = result.indexOf("id: 1\n")
        val secondIdx = result.indexOf("id: 2\n")

        assertTrue(connectedIdx < firstIdx, "connected 코멘트가 첫 이벤트보다 앞에 있어야 한다")
        assertTrue(firstIdx < secondIdx, "이벤트가 순서대로 출력되어야 한다")
    }

    @Test
    fun `initialSseFrames event blocks are separated by blank lines`() {
        val envelopes =
            listOf(
                SseEventEnvelope(id = 1, eventType = "e", payload = "x", createdAtMs = 0),
                SseEventEnvelope(id = 2, eventType = "e", payload = "y", createdAtMs = 0),
            )

        val result = initialSseFrames(envelopes)

        // SSE 스펙상 이벤트 블록 구분자는 빈 줄(\n\n)이다.
        val blocks = result.split("\n\n").filter { it.isNotEmpty() }
        // connected 코멘트 1개 + 이벤트 2개 = 3개
        assertEquals(3, blocks.size, "connected 1개 + 이벤트 블록 2개 = 3개여야 한다")
    }

    // ── heartbeat 루프 로직 검증 ────────────────────────────────────────────

    /**
     * withTimeoutOrNull 이 타임아웃으로 null 을 반환하면 keepalive 를 기록한다는 사실을
     * 실제 코루틴 가상 시간으로 검증한다.
     *
     * MemberRoutes.kt 의 핵심 분기:
     *   result == null  → ": keepalive\n\n" 기록
     *   result.isSuccess → 이벤트 프레임 기록
     */
    @Test
    fun `heartbeat loop writes keepalive comment when no event arrives within interval`() =
        runTest {
            val heartbeatIntervalMs = 30_000L
            val written = mutableListOf<String>()

            // 이벤트가 절대 오지 않는 채널
            val emptyChannel = Channel<SseEventEnvelope>(1)

            // 루프 1회만 실행하도록 플래그 사용
            var iterations = 0
            val loopJob =
                launch {
                    while (iterations < 1) {
                        val result =
                            withTimeoutOrNull(heartbeatIntervalMs) {
                                emptyChannel.receiveCatching()
                            }
                        when {
                            result == null -> {
                                written += ": keepalive\n\n"
                                iterations++
                            }
                            result.isSuccess -> {
                                val envelope = result.getOrThrow()
                                written += "id: ${envelope.id}\nevent: ${envelope.eventType}\ndata: ${envelope.payload}\n\n"
                                iterations++
                            }
                            else -> break
                        }
                    }
                }

            // 30초를 가상 시간으로 건너뛴다
            advanceTimeBy(heartbeatIntervalMs)
            advanceUntilIdle()
            loopJob.cancel()

            assertEquals(1, written.size, "keepalive 가 정확히 1회 기록되어야 한다")
            assertEquals(": keepalive\n\n", written[0])
        }

    @Test
    fun `heartbeat loop writes event frame when event arrives before interval`() =
        runTest {
            val heartbeatIntervalMs = 30_000L
            val written = mutableListOf<String>()

            val eventChannel = Channel<SseEventEnvelope>(1)
            val envelope = SseEventEnvelope(id = 42, eventType = "member_event", payload = """{"k":"v"}""", createdAtMs = 0)

            var iterations = 0
            val loopJob =
                launch {
                    while (iterations < 1) {
                        val result =
                            withTimeoutOrNull(heartbeatIntervalMs) {
                                eventChannel.receiveCatching()
                            }
                        when {
                            result == null -> {
                                written += ": keepalive\n\n"
                                iterations++
                            }
                            result.isSuccess -> {
                                val e = result.getOrThrow()
                                written += "id: ${e.id}\nevent: ${e.eventType}\ndata: ${e.payload}\n\n"
                                iterations++
                            }
                            else -> break
                        }
                    }
                }

            // 인터벌보다 훨씬 전에 이벤트를 전송한다
            advanceTimeBy(1_000L)
            eventChannel.send(envelope)
            advanceUntilIdle()
            loopJob.cancel()

            assertEquals(1, written.size, "이벤트 프레임이 정확히 1회 기록되어야 한다")
            val frame = written[0]
            assertTrue(frame.contains("id: 42\n"), "id 필드 포함")
            assertTrue(frame.contains("event: member_event\n"), "event 필드 포함")
            assertTrue(frame.contains("""data: {"k":"v"}"""), "data 필드 포함")
            assertFalse(frame.contains(": keepalive"), "keepalive 가 포함되면 안 된다")
        }

    @Test
    fun `heartbeat loop writes multiple keepalives when no events arrive`() =
        runTest {
            val heartbeatIntervalMs = 30_000L
            val written = mutableListOf<String>()

            val emptyChannel = Channel<SseEventEnvelope>(1)

            // 인터벌 2회를 타임아웃시켜 keepalive 2개를 누적한다
            val loopJob =
                launch {
                    var iterations = 0
                    while (iterations < 2) {
                        val result =
                            withTimeoutOrNull(heartbeatIntervalMs) {
                                emptyChannel.receiveCatching()
                            }
                        when {
                            result == null -> {
                                written += ": keepalive\n\n"
                                iterations++
                            }
                            result.isSuccess -> {
                                val e = result.getOrThrow()
                                written += "id: ${e.id}\nevent: ${e.eventType}\ndata: ${e.payload}\n\n"
                                iterations++
                            }
                            else -> break
                        }
                    }
                }

            // 인터벌 2회 분량의 가상 시간을 소진한다 → keepalive 2개
            advanceTimeBy(heartbeatIntervalMs * 2)
            loopJob.join()

            assertEquals(2, written.size, "인터벌 2회 → keepalive 2개")
            assertEquals(": keepalive\n\n", written[0])
            assertEquals(": keepalive\n\n", written[1])
        }

    @Test
    fun `heartbeat loop exits when channel is closed`() =
        runTest {
            val heartbeatIntervalMs = 30_000L
            val written = mutableListOf<String>()

            val closedChannel = Channel<SseEventEnvelope>(1)
            // 채널을 즉시 닫는다 (버스 종료 시뮬레이션)
            closedChannel.close()

            val loopJob =
                launch {
                    while (true) {
                        val result =
                            withTimeoutOrNull(heartbeatIntervalMs) {
                                closedChannel.receiveCatching()
                            }
                        when {
                            result == null -> written += ": keepalive\n\n"
                            result.isSuccess -> written += "event\n\n"
                            else -> break // 채널 종료 → 루프 탈출
                        }
                    }
                }

            advanceUntilIdle()
            loopJob.cancel()

            // 채널이 이미 닫혀 있으므로 keepalive 나 이벤트 없이 루프가 즉시 탈출해야 한다
            assertTrue(written.isEmpty(), "닫힌 채널에서는 아무것도 기록되면 안 된다")
        }

    // ── SseEventBus 와의 통합: openSubscriberChannel 동작 검증 ───────────────

    @Test
    fun `openSubscriberChannel returns channel that receives emitted events`() {
        val bus = SseEventBus(bufferSize = 4)
        val ch = bus.openSubscriberChannel()

        bus.emit("hello", "test")

        // 채널이 닫히지 않았고 이벤트가 들어와 있어야 한다
        assertFalse(ch.isClosedForSend, "채널이 열려 있어야 한다")
        val result = ch.tryReceive()
        assertTrue(result.isSuccess, "이미 전송된 이벤트를 즉시 수신할 수 있어야 한다")
        assertEquals("hello", result.getOrThrow().payload)

        bus.removeSubscriber(ch)
        bus.close()
    }

    @Test
    fun `removeSubscriberSuspend decrements subscriber count`() {
        val bus = SseEventBus(bufferSize = 4)
        val ch = bus.openSubscriberChannel()
        assertEquals(1, bus.subscriberCount())

        bus.removeSubscriber(ch)

        assertEquals(0, bus.subscriberCount(), "removeSubscriber 후 카운트가 0이어야 한다")
        bus.close()
    }
}
