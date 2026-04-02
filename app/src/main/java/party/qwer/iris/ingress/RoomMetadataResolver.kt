package party.qwer.iris.ingress

import party.qwer.iris.ChatLogRepository
import party.qwer.iris.IrisLogger
import party.qwer.iris.KakaoDB

internal class RoomMetadataResolver(
    private val db: ChatLogRepository,
) {
    private val roomMetadataCache = lruMap<Long, KakaoDB.RoomMetadata>(256)

    fun resolve(chatId: Long): KakaoDB.RoomMetadata =
        resolveWithCache(
            cache = roomMetadataCache,
            key = chatId,
            fallback = KakaoDB.RoomMetadata(),
            fetch = db::resolveRoomMetadata,
        )

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
                IrisLogger.debugLazy { "[RoomMetadataResolver] resolve failed for key=$key: ${e.message}" }
                return fallback
            }
        return synchronized(cache) { cache.getOrPut(key) { resolved } }
    }
}
