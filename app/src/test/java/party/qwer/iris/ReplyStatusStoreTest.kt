package party.qwer.iris

import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplyStatusStoreTest {
    @Test
    fun `expires stale snapshots`() {
        val now = AtomicLong(0L)
        val store =
            ReplyStatusStore(
                maximumSize = 10,
                expireAfterWrite = Duration.ofMinutes(30),
                tickerNanos = now::get,
            )

        store.update("req-1", "queued")
        now.addAndGet(Duration.ofMinutes(31).toNanos())

        assertNull(store.get("req-1"))
    }

    @Test
    fun `retains at most configured maximum snapshots`() {
        val store =
            ReplyStatusStore(
                maximumSize = 2,
                expireAfterWrite = Duration.ofMinutes(30),
                tickerNanos = { 0L },
            )

        store.update("req-1", "queued")
        store.update("req-2", "queued")
        store.update("req-3", "queued")

        assertTrue(store.sizeForTest() <= 2)
    }
}
