package party.qwer.iris.bridge

import android.util.Log

internal class KakaoImageSender(
    private val chatRoomResolver: ChatRoomResolver,
    private val sendInvocationFactory: KakaoSendInvocationFactory,
) {
    companion object {
        private const val TAG = "IrisBridge"
    }

    constructor(registry: KakaoClassRegistry) : this(
        chatRoomResolver = ChatRoomResolver(registry),
        sendInvocationFactory = KakaoSendInvocationFactory(registry),
    )

    fun send(request: ImageSendRequest) {
        send(
            roomId = request.roomId,
            imagePaths = request.imagePaths,
            threadId = request.threadId,
            threadScope = request.threadScope,
            requestId = request.requestId,
        )
    }

    fun send(
        roomId: Long,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ) {
        require(imagePaths.isNotEmpty()) { "no image paths" }
        Log.i(TAG, "send start room=$roomId images=${imagePaths.size} threadId=$threadId scope=$threadScope requestId=$requestId")

        val chatRoom = chatRoomResolver.resolve(roomId) ?: error("chat room not found: $roomId")

        if (imagePaths.size == 1) {
            sendInvocationFactory.sendSingle(chatRoom, imagePaths.first(), threadId, threadScope)
        } else {
            sendInvocationFactory.sendMultiple(chatRoom, imagePaths, threadId, threadScope)
        }
        Log.i(TAG, "send completed room=$roomId requestId=$requestId")
    }
}
