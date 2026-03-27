@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package party.qwer.iris.bridge

import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

internal class KakaoSendInvocationFactory(
    private val registry: KakaoClassRegistry,
    private val pathArgumentFactory: (String) -> Any = { path -> Uri.fromFile(File(path)) },
) {
    private val senderConstructorCache = ConcurrentHashMap<Class<*>, Constructor<*>>()

    fun sendSingle(
        chatRoom: Any,
        imagePath: String,
        threadId: Long?,
        threadScope: Int?,
    ) {
        require(imagePath.isNotBlank()) { "imagePath is blank" }
        val sender = newSender(chatRoom, threadId, threadScope)
        val mediaItem = registry.mediaItemConstructor.newInstance(imagePath, 0L)
        registry.singleSendMethod.invoke(sender, mediaItem, false)
    }

    fun sendMultiple(
        chatRoom: Any,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
    ) {
        require(imagePaths.isNotEmpty()) { "no image paths" }
        val sender = newSender(chatRoom, threadId, threadScope)
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

    private fun newSender(
        chatRoom: Any,
        threadId: Long?,
        threadScope: Int?,
    ): Any {
        val sendWithChatRoomInThread = threadId != null && threadScope == 3
        val loader = registry.chatMediaSenderClass.classLoader ?: error("no classLoader")
        val sendWithThreadProxy =
            Proxy.newProxyInstance(loader, arrayOf(registry.function0Class)) { _, method, _ ->
                when (method.name) {
                    "invoke" -> sendWithChatRoomInThread
                    "toString" -> "IrisBridgeSendInThread($sendWithChatRoomInThread)"
                    "hashCode" -> sendWithChatRoomInThread.hashCode()
                    "equals" -> false
                    else -> null
                }
            }
        val attachmentDecoratorProxy =
            Proxy.newProxyInstance(loader, arrayOf(registry.function1Class)) { _, method, args ->
                when (method.name) {
                    "invoke" -> args?.getOrNull(0) as? JSONObject
                    "toString" -> "IrisBridgeAttachmentDecorator"
                    "hashCode" -> 0
                    "equals" -> false
                    else -> null
                }
            }
        val constructor =
            senderConstructorCache.computeIfAbsent(chatRoom.javaClass) { chatRoomClass ->
                registry.chatMediaSenderClass.getConstructor(
                    chatRoomClass,
                    java.lang.Long::class.java,
                    registry.function0Class,
                    registry.function1Class,
                )
            }
        return constructor.newInstance(chatRoom, threadId, sendWithThreadProxy, attachmentDecoratorProxy)
    }
}
