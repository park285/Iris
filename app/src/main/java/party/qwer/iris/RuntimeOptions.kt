package party.qwer.iris

internal data class RuntimeOptions(
    val disableHttp: Boolean,
    val bindHost: String,
    val httpWorkerThreads: Int,
    val bridgeHealthRefreshMs: Long,
    val snapshotFullReconcileIntervalMs: Long,
    val snapshotMissingTombstoneTtlMs: Long?,
    val roomEventRetentionMs: Long?,
    val imageDeletionIntervalMs: Long,
    val imageRetentionMs: Long,
) {
    companion object {
        private const val DEFAULT_BIND_HOST = "127.0.0.1"
        private const val DEFAULT_HTTP_WORKER_THREADS = 2
        private const val DEFAULT_BRIDGE_HEALTH_REFRESH_MS = 5_000L
        private const val DEFAULT_SNAPSHOT_FULL_RECONCILE_INTERVAL_MS = 60_000L
        private const val DEFAULT_ROOM_EVENT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        fun fromEnv(env: Map<String, String> = System.getenv()): RuntimeOptions =
            RuntimeOptions(
                disableHttp = booleanFlagEnabled(env["IRIS_DISABLE_HTTP"]),
                bindHost = env["IRIS_BIND_HOST"]?.trim().orEmpty().ifBlank { DEFAULT_BIND_HOST },
                httpWorkerThreads = positiveIntOrDefault(env["IRIS_HTTP_WORKER_THREADS"], DEFAULT_HTTP_WORKER_THREADS),
                bridgeHealthRefreshMs =
                    positiveDurationMillisOrDefault(
                        env["IRIS_BRIDGE_HEALTH_REFRESH_MS"],
                        DEFAULT_BRIDGE_HEALTH_REFRESH_MS,
                    ),
                snapshotFullReconcileIntervalMs =
                    positiveDurationMillisOrDefault(
                        env["IRIS_SNAPSHOT_FULL_RECONCILE_INTERVAL_MS"],
                        DEFAULT_SNAPSHOT_FULL_RECONCILE_INTERVAL_MS,
                    ),
                snapshotMissingTombstoneTtlMs =
                    optionalPositiveDurationMillis(
                        env["IRIS_SNAPSHOT_MISSING_TOMBSTONE_TTL_MS"],
                    ),
                roomEventRetentionMs =
                    positiveDurationMillisOrDefault(
                        env["IRIS_ROOM_EVENT_RETENTION_MS"],
                        DEFAULT_ROOM_EVENT_RETENTION_MS,
                    ),
                imageDeletionIntervalMs =
                    positiveDurationMillisOrDefault(
                        env["IRIS_IMAGE_DELETE_INTERVAL_MS"],
                        Main.defaultImageDeletionIntervalMs,
                    ),
                imageRetentionMs =
                    positiveDurationMillisOrDefault(
                        env["IRIS_IMAGE_RETENTION_MS"],
                        Main.defaultImageRetentionMs,
                    ),
            )
    }
}

internal fun booleanFlagEnabled(rawValue: String?): Boolean =
    rawValue
        ?.trim()
        ?.lowercase()
        ?.let { normalized -> normalized == "1" || normalized == "true" || normalized == "on" }
        ?: false

internal fun positiveIntOrDefault(
    rawValue: String?,
    defaultValue: Int,
): Int = rawValue?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: defaultValue

internal fun optionalPositiveDurationMillis(rawValue: String?): Long? = rawValue?.trim()?.toLongOrNull()?.takeIf { it > 0L }
