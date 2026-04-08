@file:Suppress("ProguardSerializableOutsideModel")

package party.qwer.iris

import kotlinx.serialization.Serializable
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.UserId

internal data class LiveRoomMember(
    val userId: UserId,
    val nickname: String,
    val roleCode: Int? = null,
    val profileImageUrl: String? = null,
)

internal data class LiveRoomMemberHint(
    val userId: UserId,
    val nickname: String? = null,
)

@Serializable
internal enum class LiveSnapshotConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

@Serializable
internal data class LiveRoomMemberExtractionPlan(
    val containerPath: String,
    val sourceClassName: String? = null,
    val userIdPath: String,
    val nicknamePath: String,
    val rolePath: String? = null,
    val profileImagePath: String? = null,
    val fingerprint: String,
    val version: Int = 1,
)

internal data class LiveRoomMemberSnapshot(
    val chatId: ChatId,
    val sourcePath: String? = null,
    val sourceClassName: String? = null,
    val scannedAtEpochMs: Long,
    val members: Map<UserId, LiveRoomMember>,
    val selectedPlan: LiveRoomMemberExtractionPlan? = null,
    val confidence: LiveSnapshotConfidence = LiveSnapshotConfidence.LOW,
    val confidenceScore: Int = 0,
    val usedPreferredPlan: Boolean = false,
    val candidateGap: Int? = null,
)

internal fun interface LiveRoomMemberSnapshotProvider {
    fun snapshot(
        chatId: ChatId,
        expectedMembers: Collection<LiveRoomMemberHint>,
        preferredPlan: LiveRoomMemberExtractionPlan?,
    ): LiveRoomMemberSnapshot?
}

internal class BridgeLiveRoomMemberSnapshotProvider(
    private val bridgeClient: UdsImageBridgeClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryBackoffMs: Long = 30_000L,
) : LiveRoomMemberSnapshotProvider {
    @Volatile
    private var unsupported = false

    @Volatile
    private var nextRetryAtMs: Long = 0L

    override fun snapshot(
        chatId: ChatId,
        expectedMembers: Collection<LiveRoomMemberHint>,
        preferredPlan: LiveRoomMemberExtractionPlan?,
    ): LiveRoomMemberSnapshot? {
        if (unsupported || expectedMembers.isEmpty()) {
            return null
        }
        val now = clock()
        if (now < nextRetryAtMs) {
            return null
        }

        return runCatching {
            bridgeClient.snapshotChatRoomMembers(
                roomId = chatId.value,
                expectedMembers = expectedMembers,
                preferredPlan = preferredPlan,
            )
        }.onFailure { error ->
            if (isUnsupported(error.message)) {
                unsupported = true
                IrisLogger.warn(
                    "[BridgeLiveRoomMemberSnapshotProvider] disabling live member snapshots: ${error.message}",
                )
            } else {
                nextRetryAtMs = now + retryBackoffMs
                IrisLogger.warn(
                    "[BridgeLiveRoomMemberSnapshotProvider] live member snapshot unavailable until $nextRetryAtMs: ${error.message}",
                )
            }
        }.getOrNull()
    }

    private fun isUnsupported(message: String?): Boolean {
        val normalized = message?.lowercase().orEmpty()
        return normalized.contains("unknown action: snapshot_chatroom_members") ||
            normalized.contains("chatroom member snapshot unavailable") ||
            normalized.contains("expected member ids required")
    }

    fun snapshot(
        chatId: ChatId,
        expectedMembers: Collection<LiveRoomMemberHint>,
    ): LiveRoomMemberSnapshot? = snapshot(chatId, expectedMembers, preferredPlan = null)
}

internal fun LiveRoomMemberExtractionPlan.toProtocolPlan(): ImageBridgeProtocol.ChatRoomMemberExtractionPlan =
    ImageBridgeProtocol.ChatRoomMemberExtractionPlan(
        containerPath = containerPath,
        sourceClassName = sourceClassName,
        userIdPath = userIdPath,
        nicknamePath = nicknamePath,
        rolePath = rolePath,
        profileImagePath = profileImagePath,
        fingerprint = fingerprint,
        version = version,
    )

internal fun ImageBridgeProtocol.ChatRoomMemberExtractionPlan.toLivePlan(): LiveRoomMemberExtractionPlan =
    LiveRoomMemberExtractionPlan(
        containerPath = containerPath,
        sourceClassName = sourceClassName,
        userIdPath = userIdPath,
        nicknamePath = nicknamePath,
        rolePath = rolePath,
        profileImagePath = profileImagePath,
        fingerprint = fingerprint,
        version = version,
    )

internal fun ImageBridgeProtocol.ChatRoomSnapshotConfidence.toLiveConfidence(): LiveSnapshotConfidence =
    when (this) {
        ImageBridgeProtocol.ChatRoomSnapshotConfidence.HIGH -> LiveSnapshotConfidence.HIGH
        ImageBridgeProtocol.ChatRoomSnapshotConfidence.MEDIUM -> LiveSnapshotConfidence.MEDIUM
        ImageBridgeProtocol.ChatRoomSnapshotConfidence.LOW -> LiveSnapshotConfidence.LOW
    }

internal fun LiveSnapshotConfidence.toEvidenceConfidence(): NicknameEvidenceConfidence =
    when (this) {
        LiveSnapshotConfidence.HIGH -> NicknameEvidenceConfidence.HIGH
        LiveSnapshotConfidence.MEDIUM -> NicknameEvidenceConfidence.MEDIUM
        LiveSnapshotConfidence.LOW -> NicknameEvidenceConfidence.LOW
    }
