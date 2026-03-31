package party.qwer.iris

import kotlinx.coroutines.runBlocking

internal fun MessageSender.sendMessage(
    referer: String,
    chatId: Long,
    msg: String,
    threadId: Long?,
    threadScope: Int?,
    requestId: String? = null,
): ReplyAdmissionResult = runBlocking { sendMessageSuspend(referer, chatId, msg, threadId, threadScope, requestId) }

internal fun MessageSender.sendTextShare(
    room: Long,
    msg: String,
    requestId: String? = null,
): ReplyAdmissionResult = runBlocking { sendTextShareSuspend(room, msg, requestId) }

internal fun MessageSender.sendReplyMarkdown(
    room: Long,
    msg: String,
    threadId: Long? = null,
    threadScope: Int? = null,
    requestId: String? = null,
): ReplyAdmissionResult = runBlocking { sendReplyMarkdownSuspend(room, msg, threadId, threadScope, requestId) }

internal suspend fun MessageSender.sendNativePhotoBytesSuspend(
    room: Long,
    imageBytes: ByteArray,
    threadId: Long? = null,
    threadScope: Int? = null,
    requestId: String? = null,
): ReplyAdmissionResult =
    sendNativeMultiplePhotosBytesSuspend(
        room = room,
        imageBytesList = listOf(imageBytes),
        threadId = threadId,
        threadScope = threadScope,
        requestId = requestId,
    )

internal fun MessageSender.sendNativePhotoBytes(
    room: Long,
    imageBytes: ByteArray,
    threadId: Long? = null,
    threadScope: Int? = null,
    requestId: String? = null,
): ReplyAdmissionResult = runBlocking { sendNativePhotoBytesSuspend(room, imageBytes, threadId, threadScope, requestId) }

internal suspend fun MessageSender.sendNativeMultiplePhotosBytesSuspend(
    room: Long,
    imageBytesList: List<ByteArray>,
    threadId: Long? = null,
    threadScope: Int? = null,
    requestId: String? = null,
): ReplyAdmissionResult {
    val verifiedHandles =
        try {
            verifyImagePayloadHandles(imageBytesList)
        } catch (_: IllegalArgumentException) {
            return ReplyAdmissionResult(
                ReplyAdmissionStatus.INVALID_PAYLOAD,
                "image replies require valid binary payload",
            )
        }
    return sendNativeMultiplePhotosHandlesSuspend(
        room = room,
        imageHandles = verifiedHandles,
        threadId = threadId,
        threadScope = threadScope,
        requestId = requestId,
    )
}

internal fun MessageSender.sendNativeMultiplePhotosBytes(
    room: Long,
    imageBytesList: List<ByteArray>,
    threadId: Long? = null,
    threadScope: Int? = null,
    requestId: String? = null,
): ReplyAdmissionResult = runBlocking { sendNativeMultiplePhotosBytesSuspend(room, imageBytesList, threadId, threadScope, requestId) }
