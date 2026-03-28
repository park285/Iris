package party.qwer.iris

import kotlinx.serialization.json.Json
import party.qwer.iris.model.ReplyLifecycleState
import party.qwer.iris.model.ReplyStatusSnapshot
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplyStatusStoreTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun `expires stale snapshots`() {
        val now = AtomicLong(0L)
        val store =
            ReplyStatusStore(
                maximumSize = 10,
                expireAfterWrite = Duration.ofMinutes(30),
                tickerNanos = now::get,
            )

        store.update("req-1", ReplyLifecycleState.QUEUED)
        now.addAndGet(Duration.ofMinutes(31).toNanos())

        assertNull(store.get("req-1"))
    }

    @Test
    fun `lifecycle state serializes to lowercase json string`() {
        val snapshot =
            ReplyStatusSnapshot(
                requestId = "req-1",
                state = ReplyLifecycleState.QUEUED,
                updatedAtEpochMs = 1000L,
            )
        val encoded = json.encodeToString(ReplyStatusSnapshot.serializer(), snapshot)
        assert(encoded.contains("\"queued\"")) { "Expected 'queued' in $encoded" }
        val decoded = json.decodeFromString(ReplyStatusSnapshot.serializer(), encoded)
        assertEquals(ReplyLifecycleState.QUEUED, decoded.state)
    }

    @Test
    fun `all lifecycle states round-trip through json`() {
        for (state in ReplyLifecycleState.entries) {
            val snapshot =
                ReplyStatusSnapshot(
                    requestId = "req-${state.name}",
                    state = state,
                    updatedAtEpochMs = 1000L,
                )
            val encoded = json.encodeToString(ReplyStatusSnapshot.serializer(), snapshot)
            val decoded = json.decodeFromString(ReplyStatusSnapshot.serializer(), encoded)
            assertEquals(state, decoded.state, "Round-trip failed for $state")
        }
    }

    @Test
    fun `store update and get with typed state`() {
        val store = ReplyStatusStore()
        store.update("req-1", ReplyLifecycleState.QUEUED)
        store.update("req-1", ReplyLifecycleState.SENDING, detail = "worker-0")
        val result = store.get("req-1")!!
        assertEquals(ReplyLifecycleState.SENDING, result.state)
        assertEquals("worker-0", result.detail)
    }

    @Test
    fun `unknown state string fails deserialization`() {
        val raw = """{"requestId":"req-1","state":"unknown","updatedAtEpochMs":1000}"""
        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString(ReplyStatusSnapshot.serializer(), raw)
        }
    }

    @Test
    fun `retains at most configured maximum snapshots`() {
        val store =
            ReplyStatusStore(
                maximumSize = 2,
                expireAfterWrite = Duration.ofMinutes(30),
                tickerNanos = { 0L },
            )

        store.update("req-1", ReplyLifecycleState.QUEUED)
        store.update("req-2", ReplyLifecycleState.QUEUED)
        store.update("req-3", ReplyLifecycleState.QUEUED)

        assertTrue(store.sizeForTest() <= 2)
    }
}
