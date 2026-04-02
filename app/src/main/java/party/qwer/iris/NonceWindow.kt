package party.qwer.iris

import java.util.LinkedHashMap

internal class NonceWindow(
    private val maxAgeMs: Long,
    private val maxNonceEntries: Int,
    private val purgeIntervalMs: Long,
) {
    private val nonceTimestamps = LinkedHashMap<String, Long>()
    private var lastPurgeAt = 0L

    init {
        require(maxNonceEntries > 0) { "maxNonceEntries must be greater than zero" }
        require(purgeIntervalMs >= 0L) { "purgeIntervalMs must be non-negative" }
    }

    fun tryRecord(
        nonce: String,
        now: Long,
    ): Boolean =
        synchronized(this) {
            maybePurge(now)
            if (nonceTimestamps.containsKey(nonce)) {
                return false
            }

            if (nonceTimestamps.size >= maxNonceEntries) {
                purgeExpiredNonces(now)
                lastPurgeAt = now
                if (nonceTimestamps.size >= maxNonceEntries) {
                    return false
                }
            }

            nonceTimestamps[nonce] = now
            true
        }

    internal fun size(): Int =
        synchronized(this) {
            nonceTimestamps.size
        }

    private fun maybePurge(now: Long) {
        if (now - lastPurgeAt < purgeIntervalMs) {
            return
        }
        purgeExpiredNonces(now)
        lastPurgeAt = now
    }

    private fun purgeExpiredNonces(now: Long) {
        val cutoff = now - maxAgeMs
        nonceTimestamps.entries.removeIf { (_, seenAt) -> seenAt < cutoff }
    }
}
