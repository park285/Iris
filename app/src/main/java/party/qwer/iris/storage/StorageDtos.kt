package party.qwer.iris.storage

data class RoomRow(
    val id: Long,
    val type: String?,
    val linkId: Long?,
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
    val id: Long,
    val name: String?,
    val enc: Int,
)

data class OpenMemberRow(
    val userId: Long,
    val nickname: String?,
    val linkMemberType: Int,
    val profileImageUrl: String?,
    val enc: Int,
)

data class ObservedProfileLinkRow(
    val userId: Long,
    val displayName: String?,
)

data class MemberActivityRow(
    val userId: Long,
    val messageCount: Int,
    val lastActive: Long?,
)

data class ObservedProfileHintRow(
    val displayName: String?,
    val roomName: String?,
)

data class MessageTypeCountRow(
    val userId: Long,
    val type: String?,
    val count: Int,
    val lastActive: Long?,
)

data class MessageLogRow(
    val type: String?,
    val createdAt: Long,
)
