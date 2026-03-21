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
    ): ReplyAdmissionResult

    fun sendMultiplePhotos(
        room: Long,
        base64ImageDataStrings: List<String>,
    ): ReplyAdmissionResult
}
