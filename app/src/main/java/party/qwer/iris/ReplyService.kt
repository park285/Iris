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
import party.qwer.iris.reply.ReplyAdmissionService
import party.qwer.iris.reply.ReplyCommandFactory
import party.qwer.iris.reply.ReplyLaneJob
import party.qwer.iris.reply.ReplyLaneJobProcessor
import party.qwer.iris.reply.ReplyStatusTracker
import party.qwer.iris.reply.ReplyThreadId
import party.qwer.iris.reply.ReplyTransitionEvent
import party.qwer.iris.reply.ReplyTransport
import party.qwer.iris.reply.ShareReplyCommand
import party.qwer.iris.reply.TextReplyCommand
import party.qwer.iris.storage.ChatId
import java.io.File

internal class ReplyService(
    private val admissionService: ReplyAdmissionService,
    private val commandFactory: ReplyCommandFactory,
    private val mediaPreparationService: MediaPreparationService,
    private val transport: ReplyTransport,
    private val dispatchScheduler: DispatchScheduler,
    private val statusTracker: ReplyStatusTracker,
    private val imagePolicy: ReplyImagePolicy,
) : MessageSender {
    private constructor(components: ReplyServiceComponents) : this(
        components.admissionService,
        components.commandFactory,
        components.mediaPreparationService,
        components.transport,
        components.dispatchScheduler,
        components.statusTracker,
        components.imagePolicy,
    )

    private companion object {
        private data class ReplyServiceComponents(
            val admissionService: ReplyAdmissionService,
            val commandFactory: ReplyCommandFactory,
            val mediaPreparationService: MediaPreparationService,
            val transport: ReplyTransport,
            val dispatchScheduler: DispatchScheduler,
            val statusTracker: ReplyStatusTracker,
            val imagePolicy: ReplyImagePolicy,
        )

        private fun defaultImageMediaScanEnabled(): Boolean =
            System
                .getenv("IRIS_IMAGE_MEDIA_SCAN")
                ?.trim()
                ?.lowercase()
                ?.let { raw -> raw != "0" && raw != "false" && raw != "off" }
                ?: true

        private fun buildComponents(
            config: ReplyDispatchConfigProvider,
            nativeImageReplySender: NativeImageReplySender,
            notificationReplySender: (String, Long, CharSequence, Long?, Int?) -> Unit,
            sharedTextReplySender: (Long, CharSequence, Long?, Int?) -> Unit,
            mediaScanner: (File) -> Unit,
            imageDir: File,
            admissionDispatcher: CoroutineDispatcher,
            dispatchClock: () -> Long,
            statusTickerNanos: () -> Long,
            statusUpdatedAtEpochMs: () -> Long,
            imagePolicy: ReplyImagePolicy,
        ): ReplyServiceComponents {
            val mediaPreparationService =
                MediaPreparationService(
                    mediaScanner = mediaScanner,
                    imageDir = imageDir,
                    imageMediaScanEnabled = defaultImageMediaScanEnabled(),
                    imagePolicy = imagePolicy,
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
                        initialJobProcessor = buildAdmissionJobProcessor(dispatchScheduler, statusTracker),
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
                imagePolicy = imagePolicy,
            )
        }

        private fun buildAdmissionJobProcessor(
            dispatchScheduler: DispatchScheduler,
            statusTracker: ReplyStatusTracker,
        ): suspend (ReplyLaneJob) -> Unit =
            ReplyLaneJobProcessor(
                dispatchScheduler = dispatchScheduler,
                statusTracker = statusTracker,
            )::process
    }

    internal constructor(
        config: ReplyDispatchConfigProvider,
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
        imagePolicy: ReplyImagePolicy = ReplyImagePolicy(),
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
                imagePolicy = imagePolicy,
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
            job = TextReplyJob(commandFactory.textReply(referer, chatId, msg, threadId, threadScope, requestId)),
        )
    }

    suspend fun sendNativePhotoBytesSuspend(
        room: Long,
        imageBytes: ByteArray,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult = sendNativeMultiplePhotosBytesSuspend(room, listOf(imageBytes), threadId, threadScope, requestId)

    suspend fun sendNativeMultiplePhotosBytesSuspend(
        room: Long,
        imageBytesList: List<ByteArray>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult {
        val validatedPayloads =
            try {
                verifyImagePayloadHandles(
                    imageBytesList = imageBytesList,
                    policy = imagePolicy,
                )
            } catch (_: IllegalArgumentException) {
                return ReplyAdmissionResult(
                    ReplyAdmissionStatus.INVALID_PAYLOAD,
                    "image replies require valid binary payload",
                )
            }

        return sendNativeMultiplePhotosHandlesSuspend(
            room = room,
            imageHandles = validatedPayloads,
            threadId = threadId,
            threadScope = threadScope,
            requestId = requestId,
        )
    }

    override suspend fun sendNativeMultiplePhotosHandlesSuspend(
        room: Long,
        imageHandles: List<VerifiedImagePayloadHandle>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult {
        val validatedHandles =
            try {
                validateImagePayloadSizes(
                    imageSizes = imageHandles.map { it.sizeBytes },
                    policy = imagePolicy,
                )
                imageHandles.toList()
            } catch (_: IllegalArgumentException) {
                imageHandles.forEach { handle ->
                    runCatching { handle.close() }
                }
                return ReplyAdmissionResult(
                    ReplyAdmissionStatus.INVALID_PAYLOAD,
                    "image replies require valid binary payload",
                )
            }

        val result =
            enqueueRequest(
                chatId = room,
                threadId = threadId,
                requestId = requestId,
                job =
                    NativeImageHandleReplyJob(
                        command = commandFactory.nativeImageReply(room, validatedHandles.size, threadId, threadScope, requestId),
                        imageHandles = validatedHandles,
                    ),
            )
        return result
    }

    internal suspend fun enqueueActionSuspend(
        chatId: Long,
        threadId: Long?,
        requestId: String? = null,
        action: suspend () -> Unit,
    ): ReplyAdmissionResult =
        enqueueRequest(
            chatId = chatId,
            threadId = threadId,
            requestId = requestId,
            job = ActionReplyJob(requestId = requestId, action = action),
        )

    internal fun enqueueAction(
        chatId: Long,
        threadId: Long?,
        requestId: String? = null,
        action: suspend () -> Unit,
    ): ReplyAdmissionResult = runBlocking { enqueueActionSuspend(chatId, threadId, requestId, action) }

    override suspend fun sendTextShareSuspend(
        room: Long,
        msg: String,
        requestId: String?,
    ): ReplyAdmissionResult =
        enqueueRequest(
            chatId = room,
            threadId = null,
            requestId = requestId,
            job = ShareReplyJob(commandFactory.shareReply(room, msg, threadId = null, threadScope = null, requestId = requestId)),
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
            job =
                ShareReplyJob(
                    commandFactory.shareReply(room, msg, threadId, threadScope ?: if (threadId != null) 2 else null, requestId),
                ),
        )

    internal fun replyStatusOrNull(requestId: String): ReplyStatusSnapshot? = statusTracker.get(requestId)

    private suspend fun enqueueRequest(
        chatId: Long,
        threadId: Long?,
        requestId: String?,
        job: ReplyServiceJob,
    ): ReplyAdmissionResult {
        statusTracker.onQueued(requestId)
        val result =
            admissionService.enqueueSuspend(
                ReplyQueueKey(
                    chatId = ChatId(chatId),
                    threadId = threadId?.let(::ReplyThreadId),
                ),
                job,
            )
        if (result.status != ReplyAdmissionStatus.ACCEPTED &&
            requestId != null &&
            statusTracker.get(requestId)?.state == party.qwer.iris.model.ReplyLifecycleState.QUEUED
        ) {
            statusTracker.transition(requestId, ReplyTransitionEvent.Failed(result.message ?: result.status.name))
        }
        return result
    }

    private sealed interface ReplyServiceJob : ReplyLaneJob

    private class ActionReplyJob(
        override val requestId: String?,
        private val action: suspend () -> Unit,
    ) : ReplyServiceJob {
        override suspend fun send() {
            action()
        }
    }

    private inner class TextReplyJob(
        private val command: TextReplyCommand,
    ) : ReplyServiceJob {
        override val requestId: String? = command.requestId

        override suspend fun send() {
            transport.sendText(command)
        }
    }

    private inner class ShareReplyJob(
        private val command: ShareReplyCommand,
    ) : ReplyServiceJob {
        override val requestId: String? = command.requestId

        override suspend fun send() {
            transport.sendShare(command)
        }
    }

    private inner class NativeImageHandleReplyJob(
        private val command: NativeImageReplyCommand,
        imageHandles: List<VerifiedImagePayloadHandle>,
    ) : ReplyServiceJob {
        override val requestId: String? = command.requestId
        private lateinit var preparedImages: PreparedImages
        private var imageHandles: List<VerifiedImagePayloadHandle>? = imageHandles

        override suspend fun prepare() {
            val payloads = imageHandles ?: error("image payload handles already consumed")
            try {
                IrisLogger.info(
                    "[ReplyService] preparing streamed image reply room=${command.chatId} threadId=${command.threadId} imageCount=${command.imageCount}",
                )
                preparedImages = mediaPreparationService.prepareVerifiedHandles(command.chatId, payloads)
            } finally {
                payloads.forEach { handle ->
                    runCatching { handle.close() }
                }
                imageHandles = null
            }
        }

        override suspend fun abort() {
            imageHandles?.forEach { handle ->
                runCatching { handle.close() }
            }
            imageHandles = null
        }

        override suspend fun send() {
            IrisLogger.info(
                "[ReplyService] dispatching image reply room=${command.chatId} threadId=${command.threadId} imageCount=${preparedImages.files.size}",
            )
            transport.sendNativeImages(command, preparedImages)
        }
    }
}
