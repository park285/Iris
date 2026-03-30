package party.qwer.iris

import kotlinx.coroutines.runBlocking

interface MessageSender {
    suspend fun sendMessageSuspend(
        referer: String,
        chatId: Long,
        msg: String,
        threadId: Long?,
        threadScope: Int?,
        requestId: String? = null,
    ): ReplyAdmissionResult

    fun sendMessage(
        referer: String,
        chatId: Long,
        msg: String,
        threadId: Long?,
        threadScope: Int?,
        requestId: String? = null,
    ): ReplyAdmissionResult =
        runBlocking { sendMessageSuspend(referer, chatId, msg, threadId, threadScope, requestId) }

    suspend fun sendNativePhotoBytesSuspend(
        room: Long,
        imageBytes: ByteArray,
        threadId: Long? = null,
        threadScope: Int? = null,
        requestId: String? = null,
    ): ReplyAdmissionResult

    fun sendNativePhotoBytes(
        room: Long,
        imageBytes: ByteArray,
        threadId: Long? = null,
        threadScope: Int? = null,
        requestId: String? = null,
    ): ReplyAdmissionResult =
        runBlocking { sendNativePhotoBytesSuspend(room, imageBytes, threadId, threadScope, requestId) }

    suspend fun sendNativeMultiplePhotosBytesSuspend(
        room: Long,
        imageBytesList: List<ByteArray>,
        threadId: Long? = null,
        threadScope: Int? = null,
        requestId: String? = null,
    ): ReplyAdmissionResult

    fun sendNativeMultiplePhotosBytes(
        room: Long,
        imageBytesList: List<ByteArray>,
        threadId: Long? = null,
        threadScope: Int? = null,
        requestId: String? = null,
    ): ReplyAdmissionResult =
        runBlocking { sendNativeMultiplePhotosBytesSuspend(room, imageBytesList, threadId, threadScope, requestId) }

    suspend fun sendTextShareSuspend(
        room: Long,
        msg: String,
        requestId: String? = null,
    ): ReplyAdmissionResult

    fun sendTextShare(
        room: Long,
        msg: String,
        requestId: String? = null,
    ): ReplyAdmissionResult =
        runBlocking { sendTextShareSuspend(room, msg, requestId) }

    suspend fun sendReplyMarkdownSuspend(
        room: Long,
        msg: String,
        threadId: Long? = null,
        threadScope: Int? = null,
        requestId: String? = null,
    ): ReplyAdmissionResult

    fun sendReplyMarkdown(
        room: Long,
        msg: String,
        threadId: Long? = null,
        threadScope: Int? = null,
        requestId: String? = null,
    ): ReplyAdmissionResult =
        runBlocking { sendReplyMarkdownSuspend(room, msg, threadId, threadScope, requestId) }
}
