package party.qwer.iris

internal interface MessageSender {
    suspend fun sendMessageSuspend(
        referer: String,
        chatId: Long,
        msg: String,
        threadId: Long?,
        threadScope: Int?,
        requestId: String? = null,
    ): ReplyAdmissionResult

    suspend fun sendNativeMultiplePhotosHandlesSuspend(
        room: Long,
        imageHandles: List<VerifiedImagePayloadHandle>,
        threadId: Long? = null,
        threadScope: Int? = null,
        requestId: String? = null,
    ): ReplyAdmissionResult

    suspend fun sendTextShareSuspend(
        room: Long,
        msg: String,
        requestId: String? = null,
    ): ReplyAdmissionResult

    suspend fun sendReplyMarkdownSuspend(
        room: Long,
        msg: String,
        threadId: Long? = null,
        threadScope: Int? = null,
        requestId: String? = null,
    ): ReplyAdmissionResult
}
