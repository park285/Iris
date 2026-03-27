@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package party.qwer.iris.bridge

import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

internal class KakaoSendInvocationFactory(
    private val loader: ClassLoader,
    private val classLookup: (String) -> Class<*> = { className -> Class.forName(className, true, loader) },
    private val pathArgumentFactory: (String) -> Any = { path -> Uri.fromFile(File(path)) },
) {
    private val classCache = ConcurrentHashMap<String, Class<*>>()
    private val senderConstructorCache = ConcurrentHashMap<Class<*>, Constructor<*>>()

    private val mediaSenderClass by lazy { loadClass("bh.c") }
    private val mediaItemClass by lazy { loadClass("com.kakao.talk.model.media.MediaItem") }
    private val function0Class by lazy { loadClass("kotlin.jvm.functions.Function0") }
    private val function1Class by lazy { loadClass("kotlin.jvm.functions.Function1") }
    private val messageTypeClass by lazy {
        runCatching { loadClass("Op.EnumC16810c") }.getOrElse {
            loadClass("Op.c")
        }
    }
    private val writeTypeClass by lazy { loadClass("com.kakao.talk.manager.send.ChatSendingLogRequest\$c") }
    private val listenerClass by lazy { loadClass("com.kakao.talk.manager.send.m") }
    private val mediaItemConstructor by lazy {
        mediaItemClass.getConstructor(String::class.java, Long::class.javaPrimitiveType)
    }
    private val singleSendMethod by lazy {
        mediaSenderClass.getMethod("n", mediaItemClass, Boolean::class.javaPrimitiveType)
    }
    private val multiSendMethod by lazy {
        mediaSenderClass.getMethod(
            "p",
            List::class.java,
            messageTypeClass,
            String::class.java,
            JSONObject::class.java,
            JSONObject::class.java,
            writeTypeClass,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            listenerClass,
        )
    }
    private val photoType by lazy { enumConstant(messageTypeClass, "Photo") }
    private val multiPhotoType by lazy { enumConstant(messageTypeClass, "MultiPhoto") }
    private val writeTypeNone by lazy { enumConstant(writeTypeClass, "None") }

    fun sendSingle(
        chatRoom: Any,
        imagePath: String,
        threadId: Long?,
        threadScope: Int?,
    ) {
        require(imagePath.isNotBlank()) { "imagePath is blank" }
        val sender = newSender(chatRoom, threadId, threadScope)
        val mediaItem = mediaItemConstructor.newInstance(imagePath, 0L)
        singleSendMethod.invoke(sender, mediaItem, false)
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
                imagePaths.forEach { path ->
                    add(pathArgumentFactory(path))
                }
            }
        val type = if (imagePaths.size == 1) photoType else multiPhotoType
        multiSendMethod.invoke(sender, uris, type, null, null, null, writeTypeNone, false, false, null)
    }

    private fun newSender(
        chatRoom: Any,
        threadId: Long?,
        threadScope: Int?,
    ): Any {
        val sendWithChatRoomInThread = threadId != null && threadScope == 3
        val sendWithThreadProxy =
            Proxy.newProxyInstance(loader, arrayOf(function0Class)) { _, method, _ ->
                when (method.name) {
                    "invoke" -> sendWithChatRoomInThread
                    "toString" -> "IrisBridgeSendInThread($sendWithChatRoomInThread)"
                    "hashCode" -> sendWithChatRoomInThread.hashCode()
                    "equals" -> false
                    else -> null
                }
            }
        val attachmentDecoratorProxy =
            Proxy.newProxyInstance(loader, arrayOf(function1Class)) { _, method, args ->
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
                mediaSenderClass.getConstructor(
                    chatRoomClass,
                    java.lang.Long::class.java,
                    function0Class,
                    function1Class,
                )
            }
        return constructor.newInstance(chatRoom, threadId, sendWithThreadProxy, attachmentDecoratorProxy)
    }

    @Suppress("UNCHECKED_CAST")
    private fun enumConstant(
        enumClass: Class<*>,
        name: String,
    ): Any =
        enumClass.enumConstants?.firstOrNull { (it as Enum<*>).name == name }
            ?: error("enum constant $name not found in ${enumClass.name}")

    private fun loadClass(className: String): Class<*> =
        classCache.computeIfAbsent(className) { name ->
            classLookup(name)
        }
}
