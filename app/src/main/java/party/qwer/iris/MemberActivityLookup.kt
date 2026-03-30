package party.qwer.iris

import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.MemberActivityRow
import party.qwer.iris.storage.RoomStatsQueries
import party.qwer.iris.storage.UserId

internal class MemberActivityLookup(
    private val roomStats: RoomStatsQueries,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
) {
    private data class CacheEntry(
        val memberIds: Set<UserId>,
        val cachedAtMs: Long,
        val activityByUser: Map<UserId, MemberActivityRow>,
    )

    private val cache = mutableMapOf<ChatId, CacheEntry>()

    fun loadByUser(
        chatId: ChatId,
        memberIds: List<UserId>,
    ): Map<UserId, MemberActivityRow> {
        val memberIdSet = memberIds.toSet()
        if (memberIds.isEmpty()) {
            return emptyMap()
        }
        val nowMs = clock()
        val cached =
            synchronized(cache) {
                cache[chatId]?.takeIf {
                    it.memberIds == memberIdSet &&
                        nowMs - it.cachedAtMs <= cacheTtlMs
                }
            }
        return cached?.activityByUser
            ?: roomStats.loadMemberActivity(chatId, memberIds).associateBy { it.userId }.also { activity ->
                synchronized(cache) {
                    cache[chatId] =
                        CacheEntry(
                            memberIds = memberIdSet,
                            cachedAtMs = nowMs,
                            activityByUser = activity,
                        )
                }
            }
    }

    private companion object {
        private const val DEFAULT_CACHE_TTL_MS = 30_000L
    }
}
