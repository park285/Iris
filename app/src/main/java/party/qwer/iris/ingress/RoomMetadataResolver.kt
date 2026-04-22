package party.qwer.iris.ingress

import party.qwer.iris.ChatLogRepository
import party.qwer.iris.IrisLogger
import party.qwer.iris.KakaoDB

internal data class RoomMetadataResolution(
    val metadata: KakaoDB.RoomMetadata,
    val resolved: Boolean,
)

internal class RoomMetadataResolver(
    private val db: ChatLogRepository,
) {
    private val roomMetadataCache = lruMap<Long, KakaoDB.RoomMetadata>(256)

    fun resolve(chatId: Long): KakaoDB.RoomMetadata = resolveResult(chatId).metadata

    fun resolveResult(chatId: Long): RoomMetadataResolution {
        synchronized(roomMetadataCache) { roomMetadataCache[chatId] }?.let { cached ->
            return RoomMetadataResolution(cached, resolved = true)
        }

        val resolved =
            try {
                db.resolveRoomMetadata(chatId)
            } catch (e: Exception) {
                IrisLogger.debugLazy { "[RoomMetadataResolver] resolve failed for key=$chatId: ${e.message}" }
                return RoomMetadataResolution(KakaoDB.RoomMetadata(), resolved = false)
            }

        return RoomMetadataResolution(
            metadata = synchronized(roomMetadataCache) { roomMetadataCache.getOrPut(chatId) { resolved } },
            resolved = true,
        )
    }
}
