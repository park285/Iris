package party.qwer.iris

internal interface NativeImageReplySender {
    fun send(
        roomId: Long,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String? = null,
    )
}
