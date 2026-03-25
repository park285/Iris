package party.qwer.iris

interface MessageSender {
    fun sendMessage(
        referer: String,
        chatId: Long,
        msg: String,
        threadId: Long?,
        threadScope: Int?,
    ): ReplyAdmissionResult

    fun sendPhoto(
        room: Long,
        base64ImageDataString: String,
        threadId: Long? = null,
        threadScope: Int? = null,
    ): ReplyAdmissionResult

    fun sendMultiplePhotos(
        room: Long,
        base64ImageDataStrings: List<String>,
        threadId: Long? = null,
        threadScope: Int? = null,
    ): ReplyAdmissionResult

    fun sendTextShare(
        room: Long,
        msg: String,
    ): ReplyAdmissionResult

    fun sendReplyMarkdown(
        room: Long,
        msg: String,
    ): ReplyAdmissionResult
}
