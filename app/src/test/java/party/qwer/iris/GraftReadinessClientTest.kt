package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraftReadinessClientTest {
    @Test
    fun `parses ready daemon response`() {
        val client =
            GraftReadinessClient(
                readinessUrl = "http://127.0.0.1:17373/ready",
                fetchJson = {
                    """{"ready":true,"state":"READY","timestampMs":1711280000}"""
                },
                nowMs = { 1711281234L },
            )

        val snapshot = client.current()

        assertTrue(snapshot.ready)
        assertEquals(GraftDaemonState.READY, snapshot.state)
        assertEquals(1711281234L, snapshot.checkedAtMs)
    }

    @Test
    fun `fails closed when daemon payload is malformed`() {
        val client =
            GraftReadinessClient(
                readinessUrl = "http://127.0.0.1:17373/ready",
                fetchJson = { "not-json" },
                nowMs = { 1711281234L },
            )

        val snapshot = client.current()

        assertFalse(snapshot.ready)
        assertEquals(GraftDaemonState.DEGRADED, snapshot.state)
    }

    @Test
    fun `returns cached snapshot inside ttl`() {
        var calls = 0
        val client =
            GraftReadinessClient(
                readinessUrl = "http://127.0.0.1:17373/ready",
                fetchJson = {
                    calls += 1
                    """{"ready":true,"state":"READY","timestampMs":1711280000}"""
                },
                nowMs = {
                    if (calls == 0) {
                        1711281000L
                    } else {
                        1711281500L
                    }
                },
                cacheTtlMs = 1000L,
            )

        val first = client.current()
        val second = client.current()

        assertTrue(first.ready)
        assertTrue(second.ready)
        assertEquals(1, calls)
    }
}
