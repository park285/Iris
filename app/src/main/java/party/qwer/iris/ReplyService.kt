package party.qwer.iris

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
            imageDir: File,
            admissionDispatcher: CoroutineDispatcher,
            dispatchClock: () -> Long,
            statusTickerNanos: () -> Long,
            statusUpdatedAtEpochMs: () -> Long,
        ): ReplyServiceComponents {
            val mediaPreparationService =
                MediaPreparationService(
                    mediaScanner = mediaScanner,
                    imageDir = imageDir,
                    imageMediaScanEnabled = defaultImageMediaScanEnabled(),
                )
            val dispatchScheduler =
                DispatchScheduler(
                    baseIntervalMs = { config.messageSendRate },
                    jitterMaxMs = { config.messageSendJitterMax },
                    clock = dispatchClock,
                )
            val statusTracker =
                ReplyStatusTracker(
                    ReplyStatusStore(
                        tickerNanos = statusTickerNanos,
                        updatedAtEpochMs = statusUpdatedAtEpochMs,
                    ),
                )
            return ReplyServiceComponents(
                admissionService =
                    ReplyAdmissionService(
                        dispatcher = admissionDispatcher,
                        initialRequestProcessor = buildAdmissionRequestProcessor(dispatchScheduler, statusTracker),
                    ),
                commandFactory = ReplyCommandFactory(),
                mediaPreparationService = mediaPreparationService,
                transport =
                    ReplyTransport(
                        notificationReplySender = notificationReplySender,
                        sharedTextReplySender = sharedTextReplySender,
                        nativeImageReplySender = nativeImageReplySender,
                        mediaPreparationService = mediaPreparationService,
                    ),
                dispatchScheduler = dispatchScheduler,
                statusTracker = statusTracker,
            )
        }

        private fun buildAdmissionRequestProcessor(
            dispatchScheduler: DispatchScheduler,
            statusTracker: ReplyStatusTracker,
        ): suspend (PipelineRequest) -> Unit = { request ->
            val pipelineRequest = request as? ReplyPipelineRequest
            if (pipelineRequest == null) {
                request.prepare()
                dispatchScheduler.awaitPermit()
                request.send()
            } else {
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
        imageDir: File = File(IRIS_IMAGE_DIR_PATH),
        admissionDispatcher: CoroutineDispatcher = Dispatchers.IO,
        dispatchClock: () -> Long = System::currentTimeMillis,
        statusTickerNanos: () -> Long = System::nanoTime,
        statusUpdatedAtEpochMs: () -> Long = System::currentTimeMillis,
    ) : this(
        components =
            buildComponents(
                config = config,
                nativeImageReplySender = nativeImageReplySender,
                notificationReplySender = notificationReplySender,
                sharedTextReplySender = sharedTextReplySender,
                mediaScanner = mediaScanner,
                imageDir = imageDir,
                admissionDispatcher = admissionDispatcher,
                dispatchClock = dispatchClock,
                statusTickerNanos = statusTickerNanos,
                statusUpdatedAtEpochMs = statusUpdatedAtEpochMs,
            ),
    )

    suspend fun startSuspend() {
        admissionService.startSuspend()
    }

    suspend fun restartSuspend() {
        admissionService.restartSuspend()
    }

    suspend fun shutdownSuspend() {
        admissionService.shutdownSuspend()
    }

    fun start() {
        runBlocking { startSuspend() }
    }

    fun restart() {
        runBlocking { restartSuspend() }
    }

    fun shutdown() {
        runBlocking { shutdownSuspend() }
    }

    override suspend fun sendMessageSuspend(
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

    override suspend fun sendNativePhotoBytesSuspend(
        room: Long,
        imageBytes: ByteArray,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult = sendNativeMultiplePhotosBytesSuspend(room, listOf(imageBytes), threadId, threadScope, requestId)

    override suspend fun sendNativeMultiplePhotosBytesSuspend(
        room: Long,
        imageBytesList: List<ByteArray>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult {
        val validatedPayloads =
            try {
                validateImageBytesPayload(
                    imageBytesList = imageBytesList,
                    maxImagesPerRequest = MAX_IMAGES_PER_REQUEST,
                    maxTotalBytes = MAX_TOTAL_IMAGE_PAYLOAD_BYTES_PER_REQUEST,
                )
            } catch (_: IllegalArgumentException) {
                return ReplyAdmissionResult(
                    ReplyAdmissionStatus.INVALID_PAYLOAD,
                    "image replies require valid binary payload",
                )
            }

        return enqueueRequest(
            chatId = room,
            threadId = threadId,
            requestId = requestId,
            pipelineRequest =
                NativeImagePipelineRequest(
                    command = commandFactory.nativeImageReply(room, validatedPayloads.size, threadId, threadScope, requestId),
                    imageBytesList = validatedPayloads,
                ),
        )
    }

    internal suspend fun enqueueActionSuspend(
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

    internal fun enqueueAction(
        chatId: Long,
        threadId: Long?,
        lane: ReplySendLane = ReplySendLane.TEXT,
        requestId: String? = null,
        action: suspend () -> Unit,
    ): ReplyAdmissionResult =
        runBlocking { enqueueActionSuspend(chatId, threadId, lane, requestId, action) }

    override suspend fun sendTextShareSuspend(
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

    override suspend fun sendReplyMarkdownSuspend(
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

    private suspend fun enqueueRequest(
        chatId: Long,
        threadId: Long?,
        requestId: String?,
        pipelineRequest: ReplyPipelineRequest,
    ): ReplyAdmissionResult {
        statusTracker.onQueued(requestId)
        val result =
            admissionService.enqueueSuspend(
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
        imageBytesList: List<ByteArray>,
    ) : ReplyPipelineRequest {
        override val requestId: String? = command.requestId
        private lateinit var preparedImages: PreparedImages
        private var imageBytesList: List<ByteArray>? = imageBytesList

        override suspend fun prepare() {
            val payloads = imageBytesList ?: error("image payload bytes already consumed")
            IrisLogger.info(
                "[ReplyService] preparing image reply room=${command.chatId} threadId=${command.threadId} imageCount=${command.imageCount}",
            )
            preparedImages = mediaPreparationService.prepare(command.chatId, payloads)
            imageBytesList = null
        }

        override suspend fun send() {
            IrisLogger.info(
                "[ReplyService] dispatching image reply room=${command.chatId} threadId=${command.threadId} imageCount=${preparedImages.files.size}",
            )
            transport.sendNativeImages(command, preparedImages)
        }
    }
}
