package party.qwer.iris

import party.qwer.iris.model.MemberInfo
import party.qwer.iris.storage.UserId

internal class ObservedProfileLearningService(
    private val identityResolver: MemberIdentityResolver,
    private val learnObservedProfileUserMappings: (Long, Map<Long, String>) -> Unit,
    private val botId: Long,
) {
    fun learnMemberMappings(
        chatId: Long,
        members: List<MemberInfo>,
    ) {
        val nonBotMembers = members.filter { it.userId != botId }
        val visibleNames =
            nonBotMembers
                .asSequence()
                .mapNotNull { member ->
                    val nickname = member.nickname?.trim().orEmpty()
                    if (nickname.isBlank() || nickname == member.userId.toString()) {
                        null
                    } else {
                        member.userId to nickname
                    }
                }.toMap()
        learnMappings(chatId, visibleNames)
    }

    fun learnSnapshotNicknames(
        chatId: Long,
        nicknames: Map<UserId, String>,
    ) {
        learnLongNicknames(
            chatId = chatId,
            nicknames =
                nicknames
                    .filterValues { it.isNotBlank() }
                    .mapKeys { (userId, _) -> userId.value },
        )
    }

    fun learnLongNicknames(
        chatId: Long,
        nicknames: Map<Long, String>,
    ) {
        learnMappings(chatId, nicknames)
    }

    private fun learnMappings(
        chatId: Long,
        userDisplayNames: Map<Long, String>,
    ) {
        val learnable = identityResolver.excludeFriendResolvedUsers(userDisplayNames)
        if (learnable.isNotEmpty()) {
            learnObservedProfileUserMappings(chatId, learnable)
        }
    }
}
