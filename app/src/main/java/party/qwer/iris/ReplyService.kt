package party.qwer.iris

import android.content.Intent
import android.net.Uri
import party.qwer.iris.model.ReplyStatusSnapshot
import party.qwer.iris.reply.DispatchScheduler
import party.qwer.iris.reply.MediaPreparationService
import party.qwer.iris.reply.NativeImageReplyCommand
import party.qwer.iris.reply.PipelineRequest
import party.qwer.iris.reply.ReplyAdmissionService
import party.qwer.iris.reply.ReplyCommandFactory
import party.qwer.iris.reply.ReplyStatusTracker
import party.qwer.iris.reply.ReplyThreadId
import party.qwer.iris.reply.ReplyTransitionEvent
import party.qwer.iris.reply.ReplyTransport
import party.qwer.iris.reply.ShareReplyCommand
import party.qwer.iris.reply.TextReplyCommand
import party.qwer.iris.storage.ChatId
import java.io.File

internal enum class ReplySendLane {
    TEXT,
    NATIVE_IMAGE,
}

internal class ReplyService(
    private val admissionService: ReplyAdmissionService,
    private val commandFactory: ReplyCommandFactory,
    private val mediaPreparationService: MediaPreparationService,
    private val transport: ReplyTransport,
    private val dispatchScheduler: DispatchScheduler,
    private val statusTracker: ReplyStatusTracker,
) : MessageSender {
    private constructor(components: ReplyServiceComponents) : this(
        components.admissionService,
        components.commandFactory,
        components.mediaPreparationService,
        components.transport,
        components.dispatchScheduler,
        components.statusTracker,
    )

    private companion object {
        private const val MAX_IMAGES_PER_REQUEST = 8
        private const val MAX_TOTAL_IMAGE_PAYLOAD_BYTES_PER_REQUEST = 30 * 1024 * 1024

        private data class ReplyServiceComponents(
            val admissionService: ReplyAdmissionService,
            val commandFactory: ReplyCommandFactory,
            val mediaPreparationService: MediaPreparationService,
            val transport: ReplyTransport,
            val dispatchScheduler: DispatchScheduler,
            val statusTracker: ReplyStatusTracker,
        )

        private fun defaultImageMediaScanEnabled(): Boolean =
            System
                .getenv("IRIS_IMAGE_MEDIA_SCAN")
                ?.trim()
                ?.lowercase()
                ?.let { raw -> raw != "0" && raw != "false" && raw != "off" }
                ?: true

        private fun buildComponents(
            config: ConfigProvider,
            nativeImageReplySender: NativeImageReplySender,
            notificationReplySender: (String, Long, CharSequence, Long?, Int?) -> Unit,
            sharedTextReplySender: (Long, CharSequence, Long?, Int?) -> Unit,
            mediaScanner: (File) -> Unit,
            imageDecoder: (String) -> ByteArray,
            imageDir: File,
        ): ReplyServiceComponents {
            val mediaPreparationService =
                MediaPreparationService(
                    imageDecoder = imageDecoder,
                    mediaScanner = mediaScanner,
                    imageDir = imageDir,
                    imageMediaScanEnabled = defaultImageMediaScanEnabled(),
                )
            return ReplyServiceComponents(
                admissionService = ReplyAdmissionService(),
                commandFactory = ReplyCommandFactory(),
                mediaPreparationService = mediaPreparationService,
                transport =
                    ReplyTransport(
                        notificationReplySender = notificationReplySender,
                        sharedTextReplySender = sharedTextReplySender,
                        nativeImageReplySender = nativeImageReplySender,
                        mediaPreparationService = mediaPreparationService,
                    ),
                dispatchScheduler =
                    DispatchScheduler(
                        baseIntervalMs = { config.messageSendRate },
                        jitterMaxMs = { config.messageSendJitterMax },
                    ),
                statusTracker = ReplyStatusTracker(ReplyStatusStore()),
            )
        }
    }

    internal constructor(
        config: ConfigProvider,
        nativeImageReplySender: NativeImageReplySender = UdsImageReplySender(),
        startService: (Intent) -> Unit = { intent -> AndroidHiddenApi.startService(intent) },
        startActivityAs: (String, Intent) -> Unit = { callerPackage, intent ->
            AndroidHiddenApi.startActivityAs(callerPackage, intent)
        },
        notificationReplySender: (String, Long, CharSequence, Long?, Int?) -> Unit = { referer, chatId, preparedMessage, threadId, threadScope ->
            dispatchNotificationReply(startService, referer, chatId, preparedMessage, threadId, threadScope)
        },
        sharedTextReplySender: (Long, CharSequence, Long?, Int?) -> Unit = { room, preparedMessage, threadId, threadScope ->
            dispatchSharedTextReply(startActivityAs, room, preparedMessage, threadId, threadScope)
        },
        mediaScanner: (File) -> Unit = { file -> broadcastMediaScan(Uri.fromFile(file)) },
        imageDecoder: (String) -> ByteArray = ::decodeBase64Image,
        imageDir: File = File(IRIS_IMAGE_DIR_PATH),
    ) : this(
        components =
            buildComponents(
                config = config,
                nativeImageReplySender = nativeImageReplySender,
                notificationReplySender = notificationReplySender,
                sharedTextReplySender = sharedTextReplySender,
                mediaScanner = mediaScanner,
                imageDecoder = imageDecoder,
                imageDir = imageDir,
            ),
    )

    init {
        admissionService.onRequestProcess = ::processRequest
    }

    fun start() {
        admissionService.start()
    }

    fun restart() {
        admissionService.restart()
    }

    fun shutdown() {
        admissionService.shutdown()
    }

    override fun sendMessage(
        referer: String,
        chatId: Long,
        msg: String,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult {
        IrisLogger.debugLazy {
            "[ReplyService] sendMessage called: chatId=$chatId messageLength=${msg.length} messageHash=${msg.stableLogHash()}"
        }
        return enqueueRequest(
            chatId = chatId,
            threadId = threadId,
            requestId = requestId,
            pipelineRequest = TextPipelineRequest(commandFactory.textReply(referer, chatId, msg, threadId, threadScope, requestId)),
        )
    }

    override fun sendNativePhoto(
        room: Long,
        base64ImageDataString: String,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult = sendNativeMultiplePhotos(room, listOf(base64ImageDataString), threadId, threadScope, requestId)

    override fun sendNativeMultiplePhotos(
        room: Long,
        base64ImageDataStrings: List<String>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult {
        val payloadMetadata =
            try {
                validateImagePayloadMetadata(
                    base64ImageDataStrings,
                    maxImagesPerRequest = MAX_IMAGES_PER_REQUEST,
                    maxTotalBytes = MAX_TOTAL_IMAGE_PAYLOAD_BYTES_PER_REQUEST,
                )
            } catch (_: IllegalArgumentException) {
                return ReplyAdmissionResult(
                    ReplyAdmissionStatus.INVALID_PAYLOAD,
                    "image replies require valid base64 payload",
                )
            }

        return enqueueRequest(
            chatId = room,
            threadId = threadId,
            requestId = requestId,
            pipelineRequest =
                NativeImagePipelineRequest(
                    command = commandFactory.nativeImageReply(room, payloadMetadata.map { it.base64 }, threadId, threadScope, requestId),
                    payloadMetadata = payloadMetadata,
                ),
        )
    }

    internal fun enqueueAction(
        chatId: Long,
        threadId: Long?,
        lane: ReplySendLane = ReplySendLane.TEXT,
        requestId: String? = null,
        action: suspend () -> Unit,
    ): ReplyAdmissionResult =
        enqueueRequest(
            chatId = chatId,
            threadId = threadId,
            requestId = requestId,
            pipelineRequest = ActionPipelineRequest(requestId = requestId, action = action),
        )

    override fun sendTextShare(
        room: Long,
        msg: String,
        requestId: String?,
    ): ReplyAdmissionResult =
        enqueueRequest(
            chatId = room,
            threadId = null,
            requestId = requestId,
            pipelineRequest = SharePipelineRequest(commandFactory.shareReply(room, msg, threadId = null, threadScope = null, requestId = requestId)),
        )

    override fun sendReplyMarkdown(
        room: Long,
        msg: String,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult =
        enqueueRequest(
            chatId = room,
            threadId = threadId,
            requestId = requestId,
            pipelineRequest =
                SharePipelineRequest(
                    commandFactory.shareReply(room, msg, threadId, threadScope ?: if (threadId != null) 2 else null, requestId),
                ),
        )

    internal fun replyStatusOrNull(requestId: String): ReplyStatusSnapshot? = statusTracker.get(requestId)

    private fun enqueueRequest(
        chatId: Long,
        threadId: Long?,
        requestId: String?,
        pipelineRequest: ReplyPipelineRequest,
    ): ReplyAdmissionResult {
        statusTracker.onQueued(requestId)
        val result =
            admissionService.enqueue(
                ReplyQueueKey(
                    chatId = ChatId(chatId),
                    threadId = threadId?.let(::ReplyThreadId),
                ),
                pipelineRequest,
            )
        return if (result.status == ReplyAdmissionStatus.ACCEPTED) {
            result
        } else {
            if (requestId != null && statusTracker.get(requestId)?.state == party.qwer.iris.model.ReplyLifecycleState.QUEUED) {
                statusTracker.transition(requestId, ReplyTransitionEvent.Failed(result.message ?: result.status.name))
            }
            result
        }
    }

    private suspend fun processRequest(request: PipelineRequest) {
        val pipelineRequest =
            request as? ReplyPipelineRequest ?: run {
                request.prepare()
                dispatchScheduler.awaitPermit()
                request.send()
                return
            }
        try {
            statusTracker.transition(pipelineRequest.requestId, ReplyTransitionEvent.PrepareStarted)
            pipelineRequest.prepare()
            statusTracker.transition(pipelineRequest.requestId, ReplyTransitionEvent.PrepareCompleted)
            dispatchScheduler.awaitPermit()
            statusTracker.transition(pipelineRequest.requestId, ReplyTransitionEvent.SendStarted)
            pipelineRequest.send()
            statusTracker.transition(pipelineRequest.requestId, ReplyTransitionEvent.SendCompleted)
        } catch (e: Exception) {
            statusTracker.transition(
                pipelineRequest.requestId,
                ReplyTransitionEvent.Failed(e.message ?: "reply pipeline failure"),
            )
            IrisLogger.error("[ReplyService] pipeline send error: ${e.message}", e)
        }
    }

    private sealed interface ReplyPipelineRequest : PipelineRequest

    private class ActionPipelineRequest(
        override val requestId: String?,
        private val action: suspend () -> Unit,
    ) : ReplyPipelineRequest {
        override suspend fun send() {
            action()
        }
    }

    private inner class TextPipelineRequest(
        private val command: TextReplyCommand,
    ) : ReplyPipelineRequest {
        override val requestId: String? = command.requestId

        override suspend fun send() {
            transport.sendText(command)
        }
    }

    private inner class SharePipelineRequest(
        private val command: ShareReplyCommand,
    ) : ReplyPipelineRequest {
        override val requestId: String? = command.requestId

        override suspend fun send() {
            transport.sendShare(command)
        }
    }

    private inner class NativeImagePipelineRequest(
        private val command: NativeImageReplyCommand,
        private val payloadMetadata: List<ImagePayloadMetadata>,
    ) : ReplyPipelineRequest {
        override val requestId: String? = command.requestId
        private lateinit var preparedImages: PreparedImages

        override suspend fun prepare() {
            IrisLogger.info(
                "[ReplyService] preparing image reply room=${command.chatId} threadId=${command.threadId} imageCount=${payloadMetadata.size}",
            )
            preparedImages = mediaPreparationService.prepare(command.chatId, payloadMetadata)
        }

        override suspend fun send() {
            IrisLogger.info(
                "[ReplyService] dispatching image reply room=${command.chatId} threadId=${command.threadId} imageCount=${preparedImages.files.size}",
            )
            transport.sendNativeImages(command, preparedImages)
        }
    }
}
