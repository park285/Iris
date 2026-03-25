package party.qwer.iris

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import party.qwer.iris.model.ReplyType
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class GraftDaemonState {
    BOOTING,
    HOOKING,
    WARM,
    READY,
    DEGRADED,
    BLOCKED,
    ;

    companion object {
        fun fromWire(raw: String?): GraftDaemonState =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: DEGRADED
    }
}

data class GraftReadinessSnapshot(
    val ready: Boolean,
    val state: GraftDaemonState,
    val checkedAtMs: Long,
    val detail: String? = null,
)

fun interface GraftReadinessChecker {
    fun current(): GraftReadinessSnapshot
}

internal fun requiresThreadedImageGraft(
    replyType: ReplyType,
    threadId: Long?,
    threadScope: Int?,
): Boolean =
    (replyType == ReplyType.IMAGE || replyType == ReplyType.IMAGE_MULTIPLE) &&
        threadId != null &&
        (threadScope ?: 0) >= 2

class GraftReadinessClient(
    private val readinessUrl: String = defaultGraftReadinessUrl(),
    private val fetchJson: (String) -> String = ::defaultFetchJson,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val cacheTtlMs: Long = defaultGraftReadinessCacheTtlMs(),
) : GraftReadinessChecker {
    @Volatile
    private var cachedSnapshot: GraftReadinessSnapshot? = null

    override fun current(): GraftReadinessSnapshot {
        val now = nowMs()
        val cached = cachedSnapshot
        if (cached != null && now - cached.checkedAtMs < cacheTtlMs) {
            return cached
        }

        val fresh =
            try {
                parseGraftReadinessResponse(fetchJson(readinessUrl), now)
            } catch (e: Exception) {
                GraftReadinessSnapshot(
                    ready = false,
                    state = GraftDaemonState.DEGRADED,
                    checkedAtMs = now,
                    detail = e.message ?: "graft readiness unavailable",
                )
            }
        cachedSnapshot = fresh
        return fresh
    }
}

internal fun parseGraftReadinessResponse(
    body: String,
    checkedAtMs: Long,
): GraftReadinessSnapshot {
    val json = Json.parseToJsonElement(body).jsonObject
    val ready = json["ready"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
    val state = GraftDaemonState.fromWire(json["state"]?.jsonPrimitive?.contentOrNull)
    val detail = json["detail"]?.jsonPrimitive?.contentOrNull
    return GraftReadinessSnapshot(
        ready = ready,
        state = if (ready) state else state,
        checkedAtMs = checkedAtMs,
        detail = detail,
    )
}

private fun defaultGraftReadinessUrl(): String =
    System.getenv("IRIS_GRAFT_DAEMON_READY_URL")?.trim()?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_GRAFT_READY_URL

private fun defaultGraftReadinessCacheTtlMs(): Long =
    positiveDurationMillisOrDefault(System.getenv("IRIS_GRAFT_READY_CACHE_TTL_MS"), DEFAULT_GRAFT_READY_CACHE_TTL_MS)

private fun defaultFetchJson(url: String): String {
    val request = Request.Builder().url(url).get().build()
    httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("graft readiness http ${response.code}")
        }
        return response.body?.string() ?: throw IOException("graft readiness empty body")
    }
}

private val httpClient: OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(500, TimeUnit.MILLISECONDS)
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .callTimeout(1, TimeUnit.SECONDS)
        .build()

private const val DEFAULT_GRAFT_READY_URL = "http://127.0.0.1:17373/ready"
private const val DEFAULT_GRAFT_READY_CACHE_TTL_MS = 1000L
