package party.qwer.iris.ingress

import party.qwer.iris.ChatLogRepository
import party.qwer.iris.IrisLogger
import party.qwer.iris.MemberRepository

internal class SenderNameResolver(
    private val db: ChatLogRepository,
    private val memberRepo: MemberRepository?,
) {
    private val senderNameCache = lruMap<SenderNameCacheKey, String>(256)

    fun resolve(
        chatId: Long,
        userId: Long,
        linkId: Long?,
    ): String =
        resolveWithCache(
            cache = senderNameCache,
            key = SenderNameCacheKey(chatId = chatId, userId = userId),
            fallback = userId.toString(),
        ) { key -> resolveFresh(key.chatId, key.userId, linkId) }

    fun resolveFresh(
        chatId: Long,
        userId: Long,
        linkId: Long?,
    ): String =
        memberRepo?.resolveDisplayName(
            userId = userId,
            chatId = chatId,
            linkId = linkId,
        ) ?: db.resolveSenderName(userId)

    private fun <K, V : Any> resolveWithCache(
        cache: LinkedHashMap<K, V>,
        key: K,
        fallback: V,
        fetch: (K) -> V,
    ): V {
        synchronized(cache) { cache[key] }?.let { return it }
        val resolved =
            try {
                fetch(key)
            } catch (e: Exception) {
                IrisLogger.debugLazy { "[SenderNameResolver] resolve failed for key=$key: ${e.message}" }
                return fallback
            }
        return synchronized(cache) { cache.getOrPut(key) { resolved } }
    }
}

internal data class SenderNameCacheKey(
    val chatId: Long,
    val userId: Long,
)
