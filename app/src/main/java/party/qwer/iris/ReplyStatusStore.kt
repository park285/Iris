package party.qwer.iris

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import party.qwer.iris.model.ReplyStatusSnapshot
import java.time.Duration

internal class ReplyStatusStore(
    maximumSize: Long = 10_000,
    expireAfterWrite: Duration = Duration.ofMinutes(30),
    tickerNanos: () -> Long = Ticker.systemTicker()::read,
) {
    private val snapshots =
        Caffeine
            .newBuilder()
            .maximumSize(maximumSize)
            .expireAfterWrite(expireAfterWrite)
            .ticker(Ticker { tickerNanos() })
            .build<String, ReplyStatusSnapshot>()

    fun update(
        requestId: String,
        state: String,
        detail: String? = null,
    ) {
        snapshots.put(
            requestId,
            ReplyStatusSnapshot(
                requestId = requestId,
                state = state,
                updatedAtEpochMs = System.currentTimeMillis(),
                detail = detail,
            ),
        )
    }

    fun get(requestId: String): ReplyStatusSnapshot? = snapshots.getIfPresent(requestId)

    internal fun sizeForTest(): Long {
        snapshots.cleanUp()
        return snapshots.estimatedSize()
    }
}
