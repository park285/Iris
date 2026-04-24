package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class ImageBridgeHealthCheck(
    val name: String,
    val ok: Boolean,
    val detail: String? = null,
)

@Serializable
data class ImageBridgeDiscoveryHook(
    val name: String,
    val installed: Boolean,
    val installError: String? = null,
    val invocationCount: Int,
    val lastSeenEpochMs: Long? = null,
    val lastSummary: String? = null,
)

@Serializable
data class ImageBridgeCapability(
    val supported: Boolean = false,
    val ready: Boolean = false,
    val reason: String? = null,
)

@Serializable
data class ImageBridgeCapabilities(
    val inspectChatRoom: ImageBridgeCapability = ImageBridgeCapability(),
    val openChatRoom: ImageBridgeCapability = ImageBridgeCapability(),
    val snapshotChatRoomMembers: ImageBridgeCapability = ImageBridgeCapability(),
)

@Serializable
data class ImageBridgeHealthResult(
    val reachable: Boolean,
    val running: Boolean,
    val specReady: Boolean,
    val checkedAtEpochMs: Long? = null,
    val restartCount: Int,
    val lastCrashMessage: String? = null,
    val checks: List<ImageBridgeHealthCheck> = emptyList(),
    val discoveryInstallAttempted: Boolean = false,
    val discoveryHooks: List<ImageBridgeDiscoveryHook> = emptyList(),
    val capabilities: ImageBridgeCapabilities = ImageBridgeCapabilities(),
    val error: String? = null,
)

@Serializable
data class ChatRoomOpenResponse(
    val chatId: Long,
    val opened: Boolean,
)
