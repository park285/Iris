@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package party.qwer.iris.bridge

import android.net.Uri
import java.io.File

internal interface KakaoSendInvoker {
    fun sendSingle(
        chatRoom: Any,
        imagePath: String,
        threadId: Long?,
        threadScope: Int?,
    )

    fun sendMultiple(
        chatRoom: Any,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
    )

    fun sendThreaded(
        roomId: Long,
        chatRoom: Any,
        imagePaths: List<String>,
        threadId: Long,
        threadScope: Int,
    )
}

internal class KakaoSendInvocationFactory(
    private val registry: KakaoClassRegistry,
    private val pathArgumentFactory: (String) -> Any = { path -> Uri.fromFile(File(path)) },
) : KakaoSendInvoker {
    private val senderFactory = ChatMediaSenderInstanceFactory(registry)
    private val threadedEntryInvoker = ThreadedChatMediaEntryInvoker(registry, pathArgumentFactory)

    init {
        ThreadedImageXposedInjector.install(registry)
    }

    override fun sendSingle(
        chatRoom: Any,
        imagePath: String,
        threadId: Long?,
        threadScope: Int?,
    ) {
        require(imagePath.isNotBlank()) { "imagePath is blank" }
        val sender = senderFactory.newSender(chatRoom, threadId, threadScope)
        val mediaItem = registry.mediaItemConstructor.newInstance(imagePath, 0L)
        registry.singleSendMethod.invoke(sender, mediaItem, false)
    }

    override fun sendMultiple(
        chatRoom: Any,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
    ) {
        require(imagePaths.isNotEmpty()) { "no image paths" }
        val sender = senderFactory.newSender(chatRoom, threadId, threadScope)
        val uris =
            ArrayList<Any>(imagePaths.size).apply {
                imagePaths.forEach { path -> add(pathArgumentFactory(path)) }
            }
        val type = if (imagePaths.size == 1) registry.photoType else registry.multiPhotoType
        registry.multiSendMethod.invoke(
            sender,
            uris,
            type,
            null,
            null,
            null,
            registry.writeTypeNone,
            false,
            false,
            null,
        )
    }

    override fun sendThreaded(
        roomId: Long,
        chatRoom: Any,
        imagePaths: List<String>,
        threadId: Long,
        threadScope: Int,
    ) {
        ThreadedImageXposedInjector.withThreadContext(roomId, threadId, threadScope) {
            threadedEntryInvoker.invoke(
                sender = senderFactory.newSender(chatRoom, threadId, threadScope),
                imagePaths = imagePaths,
            )
        }
    }
}
