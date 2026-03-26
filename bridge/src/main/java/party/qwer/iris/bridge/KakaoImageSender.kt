package party.qwer.iris.bridge

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

internal class KakaoImageSender(
    private val context: Context,
    private val loader: ClassLoader,
) {
    companion object {
        private const val TAG = "IrisBridge"
    }

    fun send(
        roomId: Long,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
    ) {
        require(imagePaths.isNotEmpty()) { "no image paths" }
        Log.i(TAG, "send start room=$roomId images=${imagePaths.size} threadId=$threadId scope=$threadScope")

        val chatRoom = resolveChatRoom(roomId) ?: error("chat room not found: $roomId")

        if (imagePaths.size == 1) {
            sendSingleImage(chatRoom, imagePaths.first(), threadId, threadScope)
        } else {
            sendMultipleImages(chatRoom, imagePaths, threadId, threadScope)
        }
        Log.i(TAG, "send completed room=$roomId")
    }

    private fun sendSingleImage(
        chatRoom: Any,
        imagePath: String,
        threadId: Long?,
        threadScope: Int?,
    ) {
        val mediaSenderClass = loadClass("bh.c")
        val mediaItemClass = loadClass("com.kakao.talk.model.media.MediaItem")
        val function0Class = loadClass("kotlin.jvm.functions.Function0")
        val function1Class = loadClass("kotlin.jvm.functions.Function1")
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

        val sender =
            mediaSenderClass
                .getConstructor(
                    chatRoom.javaClass,
                    java.lang.Long::class.java,
                    function0Class,
                    function1Class,
                ).newInstance(chatRoom, threadId, sendWithThreadProxy, attachmentDecoratorProxy)

        val mediaItem =
            mediaItemClass
                .getConstructor(String::class.java, Long::class.javaPrimitiveType)
                .newInstance(imagePath, 0L)

        Log.i(TAG, "invoking ChatMediaSender.n path=$imagePath")
        mediaSenderClass
            .getMethod("n", mediaItemClass, Boolean::class.javaPrimitiveType)
            .invoke(sender, mediaItem, false)
        Log.i(TAG, "ChatMediaSender.n returned")
    }

    private fun sendMultipleImages(
        chatRoom: Any,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
    ) {
        val mediaSenderClass = loadClass("bh.c")
        val typeClass = loadChatMessageTypeClass()
        val writeTypeClass = loadClass("com.kakao.talk.manager.send.ChatSendingLogRequest\$c")
        val listenerClass = loadClass("com.kakao.talk.manager.send.m")
        val function0Class = loadClass("kotlin.jvm.functions.Function0")
        val function1Class = loadClass("kotlin.jvm.functions.Function1")
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

        val sender =
            mediaSenderClass
                .getConstructor(
                    chatRoom.javaClass,
                    java.lang.Long::class.java,
                    function0Class,
                    function1Class,
                ).newInstance(chatRoom, threadId, sendWithThreadProxy, attachmentDecoratorProxy)

        val uris = ArrayList<Uri>(imagePaths.size)
        imagePaths.forEach { path -> uris.add(Uri.fromFile(File(path))) }

        val type = enumConstant(typeClass, if (imagePaths.size == 1) "Photo" else "MultiPhoto")
        val writeTypeNone = enumConstant(writeTypeClass, "None")
        val sendMethod =
            mediaSenderClass.getMethod(
                "p",
                List::class.java,
                typeClass,
                String::class.java,
                JSONObject::class.java,
                JSONObject::class.java,
                writeTypeClass,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                listenerClass,
            )
        Log.i(TAG, "invoking ChatMediaSender.p uriCount=${uris.size}")
        sendMethod.invoke(sender, uris, type, null, null, null, writeTypeNone, false, false, null)
        Log.i(TAG, "ChatMediaSender.p returned")
    }

    private fun resolveChatRoom(roomId: Long): Any? {
        // Primary path: MasterDatabase -> roomDao.h(roomId) -> hp.t conversion
        runCatching {
            val masterDatabaseClass = loadClass("com.kakao.talk.database.MasterDatabase")
            val instanceField =
                masterDatabaseClass.declaredFields
                    .firstOrNull {
                        Modifier.isStatic(it.modifiers) && it.type == masterDatabaseClass
                    }?.apply { isAccessible = true }
                    ?: error("MasterDatabase instance field not found")
            val db = instanceField.get(null) ?: error("MasterDatabase not initialized")
            val roomDao = db.javaClass.getMethod("O").invoke(db)
            val entity =
                roomDao.javaClass
                    .getMethod("h", Long::class.javaPrimitiveType)
                    .invoke(roomDao, roomId) ?: return null
            val chatRoomClass = loadClass("hp.t")
            val companion =
                chatRoomClass.declaredFields
                    .asSequence()
                    .filter { Modifier.isStatic(it.modifiers) }
                    .mapNotNull { field ->
                        runCatching {
                            field.isAccessible = true
                            field.get(null)
                        }.getOrNull()
                    }.firstOrNull { candidate ->
                        candidate.javaClass.methods.any { method ->
                            method.name == "c" &&
                                method.parameterCount == 1 &&
                                chatRoomClass.isAssignableFrom(method.returnType) &&
                                method.parameterTypes[0].isAssignableFrom(entity.javaClass)
                        }
                    }
            if (companion != null) {
                val resolver =
                    companion.javaClass.methods.first { method ->
                        method.name == "c" &&
                            method.parameterCount == 1 &&
                            chatRoomClass.isAssignableFrom(method.returnType) &&
                            method.parameterTypes[0].isAssignableFrom(entity.javaClass)
                    }
                return resolver.invoke(companion, entity)
            }
            val ctor =
                chatRoomClass.declaredConstructors.firstOrNull { constructor ->
                    constructor.parameterTypes.size == 1 &&
                        constructor.parameterTypes[0].isAssignableFrom(entity.javaClass)
                } ?: error("hp.t companion/constructor resolver not found")
            ctor.isAccessible = true
            return ctor.newInstance(entity)
        }.onFailure {
            Log.e(TAG, "primary room resolver failed: ${it.message}", it)
        }

        // Fallback: hp.J0 singleton manager
        runCatching {
            val managerClass = loadClass("hp.J0")
            val companion =
                managerClass.declaredFields
                    .asSequence()
                    .filter { Modifier.isStatic(it.modifiers) }
                    .mapNotNull { field ->
                        runCatching {
                            field.isAccessible = true
                            field.get(null)
                        }.getOrNull()
                    }.firstOrNull { candidate ->
                        candidate.javaClass.methods.any { method ->
                            method.name == "j" &&
                                method.parameterCount == 0 &&
                                method.returnType == managerClass
                        }
                    }
            val manager =
                companion
                    ?.javaClass
                    ?.methods
                    ?.firstOrNull { method ->
                        method.name == "j" &&
                            method.parameterCount == 0 &&
                            method.returnType == managerClass
                    }?.invoke(companion)
                    ?: managerClass.methods
                        .firstOrNull { method ->
                            Modifier.isStatic(method.modifiers) &&
                                method.parameterCount == 0 &&
                                method.returnType == managerClass
                        }?.invoke(null)
                    ?: error("hp.J0 singleton accessor not found")
            val broadResolver =
                manager.javaClass.getMethod(
                    "e0",
                    Long::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
            val chatRoom = broadResolver.invoke(manager, roomId, true, true)
            if (chatRoom != null) return chatRoom
            val resolver = manager.javaClass.getMethod("d0", Long::class.javaPrimitiveType)
            return resolver.invoke(manager, roomId)
        }.onFailure {
            Log.e(TAG, "hp.J0 resolver failed: ${it.message}", it)
        }

        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun enumConstant(
        enumClass: Class<*>,
        name: String,
    ): Any =
        enumClass.enumConstants?.firstOrNull { (it as Enum<*>).name == name }
            ?: error("enum constant $name not found in ${enumClass.name}")

    private fun loadClass(className: String): Class<*> = Class.forName(className, true, loader)

    private fun loadChatMessageTypeClass(): Class<*> =
        runCatching { loadClass("Op.EnumC16810c") }.getOrElse {
            loadClass("Op.c")
        }
}
