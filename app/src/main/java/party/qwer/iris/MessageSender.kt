package party.qwer.iris

interface MessageSender {
    fun sendMessage(
        referer: String,
        chatId: Long,
        msg: String,
        threadId: Long?,
        threadScope: Int?,
        requestId: String? = null,
    ): ReplyAdmissionResult

    fun sendPhoto(
        room: Long,
        base64ImageDataString: String,
        threadId: Long? = null,
        threadScope: Int? = null,
        requestId: String? = null,
    ): ReplyAdmissionResult

    fun sendMultiplePhotos(
        room: Long,
        base64ImageDataStrings: List<String>,
        threadId: Long? = null,
        threadScope: Int? = null,
        requestId: String? = null,
    ): ReplyAdmissionResult

    fun sendNativePhoto(
        room: Long,
        base64ImageDataString: String,
        threadId: Long? = null,
        threadScope: Int? = null,
        requestId: String? = null,
    ): ReplyAdmissionResult

    fun sendNativeMultiplePhotos(
        room: Long,
        base64ImageDataStrings: List<String>,
        threadId: Long? = null,
        threadScope: Int? = null,
        requestId: String? = null,
    ): ReplyAdmissionResult

    fun sendTextShare(
        room: Long,
        msg: String,
        requestId: String? = null,
    ): ReplyAdmissionResult

    fun sendReplyMarkdown(
        room: Long,
        msg: String,
        requestId: String? = null,
    ): ReplyAdmissionResult
}
