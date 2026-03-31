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
    // 스레드의 원본(첫) 메시지 — 암호화된 원문
    val originMessage: String?,
    // 원본 메시지 작성자 userId (복호화에 사용)
    val originUserId: UserId?,
    // 원본 메시지의 origin metadata
    val originMetadata: ThreadOriginMetadata?,
)
