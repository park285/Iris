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

    /**
     * handle ownership 규약:
     * 정상 반환 이후에는 admission 결과와 무관하게 callee가 `imageHandles`를 소유한다.
     * ownership 이전에 예외가 발생한 경우에만 caller가 handle close 책임을 유지한다.
     */
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
