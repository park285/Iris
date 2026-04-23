package party.qwer.iris

import java.util.LinkedHashMap

internal class NonceWindow(
    private val maxAgeMs: Long,
    private val maxNonceEntries: Int,
    private val purgeIntervalMs: Long,
) {
    private data class NonceEntry(
        val seenAt: Long,
        val committed: Boolean,
    )

    private val nonceEntries = LinkedHashMap<String, NonceEntry>()
    private var lastPurgeAt = 0L

    init {
        require(maxNonceEntries > 0) { "maxNonceEntries must be greater than zero" }
        require(purgeIntervalMs >= 0L) { "purgeIntervalMs must be non-negative" }
    }

    fun tryReserve(
        nonce: String,
        now: Long,
    ): Boolean =
        synchronized(this) {
            maybePurge(now)
            if (nonceEntries.containsKey(nonce)) {
                return false
            }

            if (nonceEntries.size >= maxNonceEntries) {
                purgeExpiredNonces(now)
                lastPurgeAt = now
                if (nonceEntries.size >= maxNonceEntries) {
                    return false
                }
            }

            nonceEntries[nonce] = NonceEntry(seenAt = now, committed = false)
            true
        }

    fun commit(
        nonce: String,
        now: Long,
    ): Boolean =
        synchronized(this) {
            val current = nonceEntries[nonce] ?: return false
            nonceEntries[nonce] = current.copy(seenAt = now, committed = true)
            true
        }

    fun release(nonce: String) {
        synchronized(this) {
            val current = nonceEntries[nonce] ?: return
            if (!current.committed) {
                nonceEntries.remove(nonce)
            }
        }
    }

    fun tryRecord(
        nonce: String,
        now: Long,
    ): Boolean =
        synchronized(this) {
            if (!tryReserve(nonce, now)) {
                return false
            }
            commit(nonce, now)
        }

    internal fun size(): Int =
        synchronized(this) {
            nonceEntries.size
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
        nonceEntries.entries.removeIf { (_, entry) -> entry.seenAt < cutoff }
    }
}
