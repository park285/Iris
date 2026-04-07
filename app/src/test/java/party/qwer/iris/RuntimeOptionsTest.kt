package party.qwer.iris

import party.qwer.iris.model.ImageBridgeHealthResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RuntimeOptionsTest {
    @Test
    fun `runtime options use safe defaults`() {
        val options = RuntimeOptions.fromEnv(emptyMap())

        assertFalse(options.disableHttp)
        assertEquals("127.0.0.1", options.bindHost)
        assertEquals(2, options.httpWorkerThreads)
        assertEquals(5_000L, options.bridgeHealthRefreshMs)
        assertEquals(60_000L, options.snapshotFullReconcileIntervalMs)
        assertEquals(7L * 24 * 60 * 60 * 1000, options.roomEventRetentionMs)
        assertEquals(3_600_000L, options.imageDeletionIntervalMs)
        assertEquals(86_400_000L, options.imageRetentionMs)
    }

    @Test
    fun `runtime options honor environment overrides`() {
        val options =
            RuntimeOptions.fromEnv(
                mapOf(
                    "IRIS_DISABLE_HTTP" to "1",
                    "IRIS_BIND_HOST" to "0.0.0.0",
                    "IRIS_HTTP_WORKER_THREADS" to "6",
                    "IRIS_BRIDGE_HEALTH_REFRESH_MS" to "1500",
                    "IRIS_SNAPSHOT_FULL_RECONCILE_INTERVAL_MS" to "2500",
                    "IRIS_ROOM_EVENT_RETENTION_MS" to "4500",
                    "IRIS_IMAGE_DELETE_INTERVAL_MS" to "2500",
                    "IRIS_IMAGE_RETENTION_MS" to "3500",
                ),
            )

        assertTrue(options.disableHttp)
        assertEquals("0.0.0.0", options.bindHost)
        assertEquals(6, options.httpWorkerThreads)
        assertEquals(1_500L, options.bridgeHealthRefreshMs)
        assertEquals(2_500L, options.snapshotFullReconcileIntervalMs)
        assertEquals(4_500L, options.roomEventRetentionMs)
        assertEquals(2_500L, options.imageDeletionIntervalMs)
        assertEquals(3_500L, options.imageRetentionMs)
    }

    @Test
    fun `room event retention ignores empty zero and invalid overrides`() {
        val defaultRetention = 7L * 24 * 60 * 60 * 1000
        assertEquals(defaultRetention, RuntimeOptions.fromEnv(mapOf("IRIS_ROOM_EVENT_RETENTION_MS" to "")).roomEventRetentionMs)
        assertEquals(defaultRetention, RuntimeOptions.fromEnv(mapOf("IRIS_ROOM_EVENT_RETENTION_MS" to "0")).roomEventRetentionMs)
        assertEquals(defaultRetention, RuntimeOptions.fromEnv(mapOf("IRIS_ROOM_EVENT_RETENTION_MS" to "-1")).roomEventRetentionMs)
        assertEquals(defaultRetention, RuntimeOptions.fromEnv(mapOf("IRIS_ROOM_EVENT_RETENTION_MS" to "abc")).roomEventRetentionMs)
    }

    @Test
    fun `snapshot full reconcile interval ignores empty zero and invalid overrides`() {
        assertEquals(
            60_000L,
            RuntimeOptions.fromEnv(mapOf("IRIS_SNAPSHOT_FULL_RECONCILE_INTERVAL_MS" to "")).snapshotFullReconcileIntervalMs,
        )
        assertEquals(
            60_000L,
            RuntimeOptions.fromEnv(mapOf("IRIS_SNAPSHOT_FULL_RECONCILE_INTERVAL_MS" to "0")).snapshotFullReconcileIntervalMs,
        )
        assertEquals(
            60_000L,
            RuntimeOptions.fromEnv(mapOf("IRIS_SNAPSHOT_FULL_RECONCILE_INTERVAL_MS" to "-1")).snapshotFullReconcileIntervalMs,
        )
        assertEquals(
            60_000L,
            RuntimeOptions.fromEnv(mapOf("IRIS_SNAPSHOT_FULL_RECONCILE_INTERVAL_MS" to "abc")).snapshotFullReconcileIntervalMs,
        )
    }

    @Test
    fun `bridge health cache stores refreshed snapshot`() {
        val first =
            ImageBridgeHealthResult(
                reachable = true,
                running = true,
                specReady = true,
                restartCount = 1,
            )
        val cache = BridgeHealthCache(healthProvider = { first })

        cache.refreshNow()

        assertSame(first, cache.current())
        cache.stop()
    }
}
