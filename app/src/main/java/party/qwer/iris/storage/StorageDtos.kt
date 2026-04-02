package party.qwer.iris.storage

import party.qwer.iris.model.ThreadOriginMetadata

data class RoomRow(
    val id: ChatId,
    val type: String?,
    val linkId: LinkId?,
    val activeMembersCount: Int?,
    val meta: String?,
    val members: String?,
    val blindedMemberIds: String?,
    val linkName: String?,
    val linkUrl: String?,
    val memberLimit: Int?,
    val searchable: Int?,
    val botRole: Int?,
)

data class FriendRow(
    val id: UserId,
    val name: String?,
    val enc: Int,
)

data class OpenMemberRow(
    val userId: UserId,
    val nickname: String?,
    val linkMemberType: Int,
    val profileImageUrl: String?,
    val enc: Int,
)

data class ObservedProfileLinkRow(
    val userId: UserId,
    val displayName: String?,
)

data class MemberActivityRow(
    val userId: UserId,
    val messageCount: Int,
    val lastActive: Long?,
)

data class ObservedProfileHintRow(
    val displayName: String?,
    val roomName: String?,
)

data class MessageTypeCountRow(
    val userId: UserId,
    val type: String?,
    val count: Int,
    val lastActive: Long?,
)

data class MessageLogRow(
    val type: String?,
    val createdAt: Long,
)

data class ThreadRow(
    val threadId: Long,
    val messageCount: Int,
    val lastActiveAt: Long?,
    // 암호화 상태 — 복호화는 caller가 originUserId로 수행
    val originMessage: String?,
    // 복호화 키 역할
    val originUserId: UserId?,
    val originMetadata: ThreadOriginMetadata?,
)

data class RecentMessageRow(
    val id: Long,
    val chatId: ChatId,
    val userId: UserId,
    val message: String?,
    val type: Int,
    val createdAt: Long,
    val threadId: Long?,
    val metadata: ThreadOriginMetadata?,
)
