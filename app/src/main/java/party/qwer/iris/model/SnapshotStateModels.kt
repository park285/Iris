package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
internal data class PersistedSnapshotPayload(
    val chatId: Long,
    val linkId: Long? = null,
    val memberIds: List<Long> = emptyList(),
    val blindedIds: List<Long> = emptyList(),
    val nicknames: List<PersistedSnapshotStringEntry> = emptyList(),
    val roles: List<PersistedSnapshotRoleEntry> = emptyList(),
    val profileImages: List<PersistedSnapshotStringEntry> = emptyList(),
)

@Serializable
internal data class PersistedMissingSnapshotPayload(
    val snapshot: PersistedSnapshotPayload,
    val firstMissingAtMs: Long? = null,
    val consecutiveMisses: Int = 0,
    val confirmedAtMs: Long? = null,
)

@Serializable
internal data class PersistedSnapshotStringEntry(
    val userId: Long,
    val value: String,
)

@Serializable
internal data class PersistedSnapshotRoleEntry(
    val userId: Long,
    val role: Int,
)
