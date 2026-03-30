package party.qwer.iris

import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.MemberIdentityQueries
import party.qwer.iris.storage.ObservedProfileQueries
import party.qwer.iris.storage.UserId

internal class MemberIdentityResolver(
    private val memberIdentity: MemberIdentityQueries,
    private val observedProfile: ObservedProfileQueries,
) {
    fun resolveNickname(
        userId: UserId,
        linkId: LinkId? = null,
        chatId: ChatId? = null,
    ): String? {
        if (linkId != null) {
            val openNickname = memberIdentity.resolveOpenNickname(userId, linkId)
            if (openNickname != null) return openNickname
        }

        val friendName = memberIdentity.resolveFriendName(userId)
        if (friendName != null) return friendName

        return observedProfile.resolveDisplayNamesBatch(listOf(userId), chatId)[userId] ?: userId.value.toString()
    }

    fun resolveNicknamesBatch(
        userIds: Collection<UserId>,
        linkId: LinkId? = null,
        chatId: ChatId? = null,
    ): Map<UserId, String> {
        val orderedIds = userIds.distinct().filter { it.value > 0L }
        if (orderedIds.isEmpty()) return emptyMap()

        val resolved = LinkedHashMap<UserId, String>(orderedIds.size)
        var unresolved = orderedIds.toSet()

        if (linkId != null && unresolved.isNotEmpty()) {
            val openNicknames = memberIdentity.loadOpenNicknamesBatch(linkId, unresolved.toList())
            openNicknames.forEach { (userId, nickname) ->
                if (!nickname.isNullOrBlank()) {
                    resolved[userId] = nickname
                }
            }
            unresolved = unresolved - resolved.keys
        }

        if (unresolved.isNotEmpty()) {
            val friendNames = memberIdentity.loadFriendsBatch(unresolved.toList())
            resolved.putAll(friendNames)
            unresolved = unresolved - friendNames.keys
        }

        if (unresolved.isNotEmpty()) {
            observedProfile
                .resolveDisplayNamesBatch(unresolved.toList(), chatId)
                .forEach { (userId, displayName) ->
                    resolved[userId] = displayName
                }
        }

        return orderedIds.associateWith { userId -> resolved[userId] ?: userId.value.toString() }
    }

    fun prepareNicknameLookup(
        userIds: Collection<UserId>,
        linkId: LinkId? = null,
        chatId: ChatId? = null,
    ): Map<UserId, String> {
        val orderedIds = userIds.distinct().filter { it.value > 0L }
        if (orderedIds.isEmpty()) return emptyMap()
        return if (linkId != null) {
            memberIdentity
                .loadOpenNicknamesBatch(linkId, orderedIds)
                .mapValues { (userId, nickname) -> nickname ?: userId.value.toString() }
        } else {
            resolveNicknamesBatch(orderedIds, chatId = chatId)
        }
    }

    fun excludeFriendResolvedUsers(userDisplayNames: Map<Long, String>): Map<Long, String> {
        if (userDisplayNames.isEmpty()) return userDisplayNames
        val friendIds =
            memberIdentity
                .loadFriendsBatch(userDisplayNames.keys.map(::UserId))
                .keys
                .mapTo(mutableSetOf()) { it.value }
        return if (friendIds.isEmpty()) userDisplayNames else userDisplayNames.filterKeys { it !in friendIds }
    }
}
