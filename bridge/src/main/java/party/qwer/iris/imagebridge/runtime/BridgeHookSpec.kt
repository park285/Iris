package party.qwer.iris.imagebridge.runtime

import org.json.JSONArray
import org.json.JSONObject

internal data class BridgeSpecCheck(
    val name: String,
    val ok: Boolean,
    val detail: String? = null,
)

internal data class BridgeSpecStatus(
    val ready: Boolean,
    val checkedAtEpochMs: Long,
    val checks: List<BridgeSpecCheck>,
)

internal data class ImageBridgeHealthSnapshot(
    val running: Boolean,
    val specStatus: BridgeSpecStatus,
    val discoverySnapshot: BridgeDiscoverySnapshot,
    val capabilities: ImageBridgeCapabilitiesSnapshot = ImageBridgeCapabilitiesSnapshot(),
    val restartCount: Int,
    val lastCrashMessage: String?,
)

internal data class ImageBridgeCapabilitySnapshot(
    val supported: Boolean = false,
    val ready: Boolean = false,
    val reason: String? = null,
)

internal data class ImageBridgeCapabilitiesSnapshot(
    val inspectChatRoom: ImageBridgeCapabilitySnapshot = ImageBridgeCapabilitySnapshot(),
    val openChatRoom: ImageBridgeCapabilitySnapshot = ImageBridgeCapabilitySnapshot(),
    val snapshotChatRoomMembers: ImageBridgeCapabilitySnapshot = ImageBridgeCapabilitySnapshot(),
)

internal class BridgeHookSpecVerifier(
    private val registry: KakaoClassRegistry?,
    private val registryError: String? = null,
) {
    fun verify(): BridgeSpecStatus {
        val checks = mutableListOf<BridgeSpecCheck>()

        if (registry == null) {
            checks +=
                BridgeSpecCheck(
                    "KakaoClassRegistry",
                    ok = false,
                    detail = registryError ?: "not initialized",
                )
            return BridgeSpecStatus(
                ready = false,
                checkedAtEpochMs = System.currentTimeMillis(),
                checks = checks,
            )
        }

        checks += checkField("class ChatMediaSender") { registry.chatMediaSenderClass.name }
        checks += checkField("class MessageType") { registry.messageTypeClass.name }
        checks += checkField("class ChatRoomManager") { registry.chatRoomManagerClass.name }
        checks += checkField("class ChatRoom") { registry.chatRoomClass.name }
        checks += checkField("class MasterDatabase") { registry.masterDatabaseClass.name }
        checks += checkField("class MediaItem") { registry.mediaItemClass.name }
        checks += checkField("class Function0") { registry.function0Class.name }
        checks += checkField("class Function1") { registry.function1Class.name }
        checks += checkField("class WriteType") { registry.writeTypeClass.name }
        checks += checkField("class Listener") { registry.listenerClass.name }
        checks += checkField("MasterDatabase singleton field") { registry.masterDbSingletonField.name }
        checks += checkField("MasterDatabase#roomDao") { registry.roomDaoMethod.name }
        checks += checkField("RoomDao#entityLookup") { registry.entityLookupMethod.name }
        checks += checkField("ChatMediaSender#sendSingle") { registry.singleSendMethod.name }
        checks += checkField("ChatMediaSender#sendMultiple") { registry.multiSendMethod.name }
        checks += checkField("MediaItem(String,long)") { registry.mediaItemConstructor.toString() }
        checks += checkField("ChatRoomManager#broadResolve") { registry.broadRoomResolverMethod.name }
        checks += checkField("ChatRoomManager#directResolve") { registry.directRoomResolverMethod.name }
        checks += checkField("message type Photo") { registry.photoType.toString() }
        checks += checkField("message type MultiPhoto") { registry.multiPhotoType.toString() }
        checks += checkField("write type None") { registry.writeTypeNone.toString() }

        return BridgeSpecStatus(
            ready = checks.all { it.ok },
            checkedAtEpochMs = System.currentTimeMillis(),
            checks = checks,
        )
    }

    private fun checkField(
        name: String,
        accessor: () -> String,
    ): BridgeSpecCheck =
        try {
            val detail = accessor()
            BridgeSpecCheck(name, ok = true, detail = detail)
        } catch (error: Throwable) {
            BridgeSpecCheck(name, ok = false, detail = error.message ?: error.javaClass.name)
        }
}

internal fun ImageBridgeHealthSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("status", party.qwer.iris.ImageBridgeProtocol.STATUS_OK)
        put("running", running)
        put("specReady", specStatus.ready)
        put("checkedAtEpochMs", specStatus.checkedAtEpochMs)
        put("restartCount", restartCount)
        if (!lastCrashMessage.isNullOrBlank()) {
            put("lastCrashMessage", lastCrashMessage)
        }
        put(
            "checks",
            JSONArray(
                specStatus.checks.map { check ->
                    JSONObject().apply {
                        put("name", check.name)
                        put("ok", check.ok)
                        if (!check.detail.isNullOrBlank()) {
                            put("detail", check.detail)
                        }
                    }
                },
            ),
        )
        put(
            "discovery",
            JSONObject().apply {
                put("installAttempted", discoverySnapshot.installAttempted)
                put(
                    "hooks",
                    JSONArray(
                        discoverySnapshot.hooks.map { hook ->
                            JSONObject().apply {
                                put("name", hook.name)
                                put("installed", hook.installed)
                                if (!hook.installError.isNullOrBlank()) {
                                    put("installError", hook.installError)
                                }
                                put("invocationCount", hook.invocationCount)
                                if (hook.lastSeenEpochMs != null) {
                                    put("lastSeenEpochMs", hook.lastSeenEpochMs)
                                }
                                if (!hook.lastSummary.isNullOrBlank()) {
                                    put("lastSummary", hook.lastSummary)
                                }
                            }
                        },
                    ),
                )
            },
        )
        put(
            "capabilities",
            JSONObject().apply {
                put(
                    "inspectChatRoom",
                    JSONObject().apply {
                        put("supported", capabilities.inspectChatRoom.supported)
                        put("ready", capabilities.inspectChatRoom.ready)
                        capabilities.inspectChatRoom.reason?.let { put("reason", it) }
                    },
                )
                put(
                    "openChatRoom",
                    JSONObject().apply {
                        put("supported", capabilities.openChatRoom.supported)
                        put("ready", capabilities.openChatRoom.ready)
                        capabilities.openChatRoom.reason?.let { put("reason", it) }
                    },
                )
                put(
                    "snapshotChatRoomMembers",
                    JSONObject().apply {
                        put("supported", capabilities.snapshotChatRoomMembers.supported)
                        put("ready", capabilities.snapshotChatRoomMembers.ready)
                        capabilities.snapshotChatRoomMembers.reason?.let { put("reason", it) }
                    },
                )
            },
        )
    }

internal fun ImageBridgeHealthSnapshot.toProtocolResponse(): party.qwer.iris.ImageBridgeProtocol.ImageBridgeResponse =
    party.qwer.iris.ImageBridgeProtocol.ImageBridgeResponse(
        status = party.qwer.iris.ImageBridgeProtocol.STATUS_OK,
        running = running,
        specReady = specStatus.ready,
        checkedAtEpochMs = specStatus.checkedAtEpochMs,
        restartCount = restartCount,
        lastCrashMessage = lastCrashMessage,
        checks =
            specStatus.checks.map { check ->
                party.qwer.iris.ImageBridgeProtocol.ImageBridgeCheck(
                    name = check.name,
                    ok = check.ok,
                    detail = check.detail,
                )
            },
        discovery =
            party.qwer.iris.ImageBridgeProtocol.ImageBridgeDiscovery(
                installAttempted = discoverySnapshot.installAttempted,
                hooks =
                    discoverySnapshot.hooks.map { hook ->
                        party.qwer.iris.ImageBridgeProtocol.ImageBridgeDiscoveryHook(
                            name = hook.name,
                            installed = hook.installed,
                            installError = hook.installError,
                            invocationCount = hook.invocationCount,
                            lastSeenEpochMs = hook.lastSeenEpochMs,
                            lastSummary = hook.lastSummary,
                        )
                    },
            ),
        capabilities =
            party.qwer.iris.ImageBridgeProtocol.ImageBridgeCapabilities(
                inspectChatRoom =
                    party.qwer.iris.ImageBridgeProtocol.ImageBridgeCapability(
                        supported = capabilities.inspectChatRoom.supported,
                        ready = capabilities.inspectChatRoom.ready,
                        reason = capabilities.inspectChatRoom.reason,
                    ),
                openChatRoom =
                    party.qwer.iris.ImageBridgeProtocol.ImageBridgeCapability(
                        supported = capabilities.openChatRoom.supported,
                        ready = capabilities.openChatRoom.ready,
                        reason = capabilities.openChatRoom.reason,
                    ),
                snapshotChatRoomMembers =
                    party.qwer.iris.ImageBridgeProtocol.ImageBridgeCapability(
                        supported = capabilities.snapshotChatRoomMembers.supported,
                        ready = capabilities.snapshotChatRoomMembers.ready,
                        reason = capabilities.snapshotChatRoomMembers.reason,
                    ),
            ),
    )
