package party.qwer.iris

import android.net.LocalSocket
import android.net.LocalSocketAddress
import party.qwer.iris.ImageBridgeProtocol
import party.qwer.iris.model.ImageBridgeCapabilities
import party.qwer.iris.model.ImageBridgeCapability
import party.qwer.iris.model.ImageBridgeDiscoveryHook
import party.qwer.iris.model.ImageBridgeHealthCheck
import party.qwer.iris.model.ImageBridgeHealthResult
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.UserId
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal data class ImageBridgeResult(
    val success: Boolean,
    val error: String? = null,
)

internal interface BridgeSocket : AutoCloseable {
    val inputStream: InputStream
    val outputStream: OutputStream

    @Throws(IOException::class)
    fun connect(
        socketName: String,
        timeoutMs: Int,
    )

    @Throws(IOException::class)
    fun connect(socketName: String)

    fun setReadTimeout(timeoutMs: Int)

    @Throws(IOException::class)
    fun shutdownOutput()
}

private class AndroidBridgeSocket : BridgeSocket {
    private val socket = LocalSocket()

    override val inputStream: InputStream
        get() = socket.inputStream

    override val outputStream: OutputStream
        get() = socket.outputStream

    override fun connect(
        socketName: String,
        timeoutMs: Int,
    ) {
        socket.connect(
            LocalSocketAddress(
                socketName,
                LocalSocketAddress.Namespace.ABSTRACT,
            ),
            timeoutMs,
        )
    }

    override fun connect(socketName: String) {
        socket.connect(
            LocalSocketAddress(
                socketName,
                LocalSocketAddress.Namespace.ABSTRACT,
            ),
        )
    }

    override fun setReadTimeout(timeoutMs: Int) {
        socket.soTimeout = timeoutMs
    }

    override fun shutdownOutput() {
        socket.shutdownOutput()
    }

    override fun close() {
        socket.close()
    }
}

internal class UdsImageBridgeClient(
    private val socketName: String = ImageBridgeProtocol.SOCKET_NAME,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 30_000,
    private val bridgeToken: String = resolveBridgeToken(),
    private val securityModeRaw: String? = System.getenv("IRIS_BRIDGE_SECURITY_MODE"),
    private val socketFactory: () -> BridgeSocket = { AndroidBridgeSocket() },
) {
    init {
        if (bridgeToken.isBlank()) {
            if (isProductionSecurityMode(securityModeRaw)) {
                IrisLogger.error("[UdsImageBridgeClient] IRIS_BRIDGE_TOKEN must be configured in production mode")
            } else {
                IrisLogger.warn("[UdsImageBridgeClient] IRIS_BRIDGE_TOKEN is not configured; bridge requests will be unauthenticated in development mode")
            }
        }
    }

    fun sendImage(
        roomId: Long,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String? = null,
    ): ImageBridgeResult {
        try {
            return parseSendResponse(
                exchange(
                    ImageBridgeProtocol.buildSendImageRequest(
                        roomId,
                        imagePaths,
                        threadId,
                        threadScope,
                        requestId,
                        bridgeToken,
                    ),
                ),
            )
        } catch (e: IOException) {
            return ImageBridgeResult(
                success = false,
                error = "bridge connection failed: ${e.message}",
            )
        } catch (e: RuntimeException) {
            return ImageBridgeResult(
                success = false,
                error = "bridge protocol failed: ${e.message}",
            )
        }
    }

    fun queryHealth(): ImageBridgeHealthResult =
        try {
            parseHealthResponse(exchange(ImageBridgeProtocol.buildHealthRequest(token = bridgeToken)))
        } catch (e: IOException) {
            ImageBridgeHealthResult(
                reachable = false,
                running = false,
                specReady = false,
                restartCount = 0,
                error = "bridge connection failed: ${e.message}",
            )
        } catch (e: RuntimeException) {
            ImageBridgeHealthResult(
                reachable = false,
                running = false,
                specReady = false,
                restartCount = 0,
                error = "bridge protocol failed: ${e.message}",
            )
        }

    fun inspectChatRoom(roomId: Long): String =
        try {
            parseInspectResponse(
                exchange(
                    ImageBridgeProtocol.buildInspectChatRoomRequest(
                        roomId = roomId,
                        token = bridgeToken,
                    ),
                ),
            )
        } catch (e: IOException) {
            error("bridge connection failed: ${e.message}")
        } catch (e: RuntimeException) {
            error("bridge protocol failed: ${e.message}")
        }

    fun openChatRoom(roomId: Long): ImageBridgeResult {
        try {
            return parseOkResponse(
                exchange(
                    ImageBridgeProtocol.buildOpenChatRoomRequest(
                        roomId = roomId,
                        token = bridgeToken,
                    ),
                ),
            )
        } catch (e: IOException) {
            return ImageBridgeResult(
                success = false,
                error = "bridge connection failed: ${e.message}",
            )
        } catch (e: RuntimeException) {
            return ImageBridgeResult(
                success = false,
                error = "bridge protocol failed: ${e.message}",
            )
        }
    }

    fun snapshotChatRoomMembers(
        roomId: Long,
        expectedMembers: Collection<LiveRoomMemberHint> = emptyList(),
        preferredPlan: LiveRoomMemberExtractionPlan? = null,
    ): LiveRoomMemberSnapshot =
        try {
            parseMemberSnapshotResponse(
                exchange(
                    ImageBridgeProtocol.buildSnapshotChatRoomMembersRequest(
                        roomId = roomId,
                        memberIds = expectedMembers.map { it.userId.value }.distinct().sorted(),
                        memberHints =
                            expectedMembers.map { hint ->
                                ImageBridgeProtocol.ChatRoomMemberHint(
                                    userId = hint.userId.value,
                                    nickname = hint.nickname?.trim()?.takeIf { it.isNotBlank() },
                                )
                            },
                        preferredMemberPlan = preferredPlan?.toProtocolPlan(),
                        token = bridgeToken,
                    ),
                ),
            )
        } catch (e: IOException) {
            error("bridge connection failed: ${e.message}")
        } catch (e: RuntimeException) {
            error("bridge protocol failed: ${e.message}")
        }

    private fun exchange(request: ImageBridgeProtocol.ImageBridgeRequest): ImageBridgeProtocol.ImageBridgeResponse {
        val socket = socketFactory()
        try {
            try {
                socket.connect(socketName, connectTimeoutMs)
            } catch (_: UnsupportedOperationException) {
                socket.connect(socketName)
            }
            socket.setReadTimeout(readTimeoutMs)

            ImageBridgeProtocol.writeFrame(socket.outputStream, request)
            socket.shutdownOutput()

            return ImageBridgeProtocol.readResponseFrame(socket.inputStream)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun parseSendResponse(response: ImageBridgeProtocol.ImageBridgeResponse): ImageBridgeResult {
        val status = response.status
        return if (status == ImageBridgeProtocol.STATUS_SENT) {
            ImageBridgeResult(success = true)
        } else {
            ImageBridgeResult(
                success = false,
                error = response.error ?: "unknown bridge error",
            )
        }
    }

    private fun parseHealthResponse(response: ImageBridgeProtocol.ImageBridgeResponse): ImageBridgeHealthResult {
        val status = response.status
        if (status != ImageBridgeProtocol.STATUS_OK) {
            return ImageBridgeHealthResult(
                reachable = true,
                running = false,
                specReady = false,
                restartCount = 0,
                error = response.error ?: "unknown bridge health error",
            )
        }
        return ImageBridgeHealthResult(
            reachable = true,
            running = response.running ?: false,
            specReady = response.specReady ?: false,
            checkedAtEpochMs = response.checkedAtEpochMs,
            restartCount = response.restartCount ?: 0,
            lastCrashMessage = response.lastCrashMessage,
            checks =
                response.checks.map { item ->
                    ImageBridgeHealthCheck(
                        name = item.name,
                        ok = item.ok,
                        detail = item.detail,
                    )
                },
            discoveryInstallAttempted = response.discovery?.installAttempted ?: false,
            discoveryHooks =
                response
                    .discovery
                    ?.hooks
                    ?.map { item ->
                        ImageBridgeDiscoveryHook(
                            name = item.name,
                            installed = item.installed,
                            installError = item.installError,
                            invocationCount = item.invocationCount,
                            lastSeenEpochMs = item.lastSeenEpochMs,
                            lastSummary = item.lastSummary,
                        )
                    }.orEmpty(),
            capabilities =
                response.capabilities?.let { capabilities ->
                    ImageBridgeCapabilities(
                        inspectChatRoom =
                            ImageBridgeCapability(
                                supported = capabilities.inspectChatRoom.supported,
                                ready = capabilities.inspectChatRoom.ready,
                                reason = capabilities.inspectChatRoom.reason,
                            ),
                        openChatRoom =
                            ImageBridgeCapability(
                                supported = capabilities.openChatRoom.supported,
                                ready = capabilities.openChatRoom.ready,
                                reason = capabilities.openChatRoom.reason,
                            ),
                        snapshotChatRoomMembers =
                            ImageBridgeCapability(
                                supported = capabilities.snapshotChatRoomMembers.supported,
                                ready = capabilities.snapshotChatRoomMembers.ready,
                                reason = capabilities.snapshotChatRoomMembers.reason,
                            ),
                    )
                } ?: ImageBridgeCapabilities(),
        )
    }

    private fun parseInspectResponse(response: ImageBridgeProtocol.ImageBridgeResponse): String {
        require(response.status == ImageBridgeProtocol.STATUS_OK) {
            response.error ?: "unknown bridge inspection error"
        }
        return response.inspectionJson ?: error("bridge inspection payload missing")
    }

    private fun parseOkResponse(response: ImageBridgeProtocol.ImageBridgeResponse): ImageBridgeResult {
        val status = response.status
        return if (status == ImageBridgeProtocol.STATUS_OK) {
            ImageBridgeResult(success = true)
        } else {
            ImageBridgeResult(
                success = false,
                error = response.error ?: "unknown bridge error",
            )
        }
    }

    private fun parseMemberSnapshotResponse(response: ImageBridgeProtocol.ImageBridgeResponse): LiveRoomMemberSnapshot {
        require(response.status == ImageBridgeProtocol.STATUS_OK) {
            response.error ?: "unknown bridge member snapshot error"
        }
        val payload = response.memberSnapshot ?: error("bridge member snapshot payload missing")
        return LiveRoomMemberSnapshot(
            chatId = ChatId(payload.roomId),
            sourcePath = payload.sourcePath,
            sourceClassName = payload.sourceClassName,
            scannedAtEpochMs = payload.scannedAtEpochMs,
            members =
                payload.members
                    .associate { item ->
                        val userId = UserId(item.userId)
                        userId to
                            LiveRoomMember(
                                userId = userId,
                                nickname = item.nickname,
                                roleCode = item.roleCode,
                                profileImageUrl = item.profileImageUrl,
                            )
                    },
            selectedPlan = payload.selectedPlan?.toLivePlan(),
            confidence = payload.confidence.toLiveConfidence(),
            confidenceScore = payload.confidenceScore,
            usedPreferredPlan = payload.usedPreferredPlan,
            candidateGap = payload.candidateGap,
        )
    }

    private fun isProductionSecurityMode(raw: String?): Boolean =
        when (raw?.trim()?.lowercase()) {
            "development",
            "dev",
            -> false
            else -> true
        }
}
