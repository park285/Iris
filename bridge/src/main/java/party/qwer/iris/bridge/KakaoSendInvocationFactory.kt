@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package party.qwer.iris.bridge

import android.net.Uri
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    private val senderConstructorCache = ConcurrentHashMap<Class<*>, Constructor<*>>()

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
        val sender = newSender(chatRoom, threadId, threadScope)
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

    override fun sendThreaded(
        roomId: Long,
        chatRoom: Any,
        imagePaths: List<String>,
        threadId: Long,
        threadScope: Int,
    ) {
        ThreadedImageXposedInjector.withThreadContext(roomId, threadId, threadScope) {
            if (imagePaths.size == 1) {
                sendSingle(chatRoom, imagePaths.first(), threadId, threadScope)
            } else {
                sendMultiple(chatRoom, imagePaths, threadId, threadScope)
            }
        }
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
                resolveSenderConstructor(chatRoomClass)
            }
        return constructor.newInstance(chatRoom, threadId, sendWithThreadProxy, attachmentDecoratorProxy)
    }

    private fun resolveSenderConstructor(chatRoomClass: Class<*>): Constructor<*> {
        val candidates =
            registry.chatMediaSenderClass.constructors.filter { constructor ->
                val parameterTypes = constructor.parameterTypes
                parameterTypes.size == 4 &&
                    parameterTypes[0].isAssignableFrom(chatRoomClass) &&
                    parameterTypes[1] == java.lang.Long::class.java &&
                    parameterTypes[2] == registry.function0Class &&
                    parameterTypes[3] == registry.function1Class
            }
        check(candidates.isNotEmpty()) {
            "ChatMediaSender constructor not found for chatRoom=${chatRoomClass.name}"
        }
        val bestDistance = candidates.minOf { constructor -> typeDistance(chatRoomClass, constructor.parameterTypes[0]) }
        val bestCandidates =
            candidates.filter { constructor ->
                typeDistance(chatRoomClass, constructor.parameterTypes[0]) == bestDistance
            }
        check(bestCandidates.size == 1) {
            val signatures =
                bestCandidates.joinToString { constructor ->
                    constructor.parameterTypes.joinToString(
                        prefix = "${constructor.declaringClass.name}(",
                        postfix = ")",
                    ) { parameterType -> parameterType.name }
                }
            "ChatMediaSender constructor is ambiguous for chatRoom=${chatRoomClass.name}: $signatures"
        }
        return bestCandidates.single()
    }

    private fun typeDistance(
        actualType: Class<*>,
        candidateType: Class<*>,
    ): Int {
        if (actualType == candidateType) return 0
        if (!candidateType.isAssignableFrom(actualType)) return Int.MAX_VALUE

        val visited = linkedSetOf<Class<*>>(actualType)
        val queue = ArrayDeque<Pair<Class<*>, Int>>()
        queue += actualType to 0
        while (queue.isNotEmpty()) {
            val (current, distance) = queue.removeFirst()
            if (current == candidateType) return distance

            current.superclass?.let { superclass ->
                if (visited.add(superclass)) {
                    queue += superclass to distance + 1
                }
            }
            current.interfaces.forEach { iface ->
                if (visited.add(iface)) {
                    queue += iface to distance + 1
                }
            }
        }
        return Int.MAX_VALUE
    }
}

internal object ThreadedImageXposedInjector {
    private const val TAG = "IrisBridge"
    private val installed = AtomicBoolean(false)
    private val pendingContext = ThreadLocal<PendingContext?>()

    private data class PendingContext(
        val roomId: Long,
        val threadId: Long,
        val threadScope: Int,
    )

    fun install(registry: KakaoClassRegistry) {
        if (!installed.compareAndSet(false, true)) return
        val injectMethod =
            selectInjectMethod(registry.chatMediaSenderClass, registry.writeTypeClass, registry.listenerClass)
                ?: run {
                    return
                }
        XposedBridge.hookMethod(
            injectMethod,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val context = pendingContext.get() ?: return
                    val sendingLog = param.args.getOrNull(0) ?: return
                    runCatching {
                        injectThreadMetadata(sendingLog, context)
                    }.onFailure { error ->
                        Log.e(TAG, "thread metadata injection failed room=${context.roomId}", error)
                    }
                }
            },
        )
    }

    fun <T> withThreadContext(
        roomId: Long,
        threadId: Long,
        threadScope: Int,
        block: () -> T,
    ): T {
        pendingContext.set(PendingContext(roomId, threadId, threadScope))
        return try {
            block()
        } finally {
            pendingContext.remove()
        }
    }

    private fun selectInjectMethod(
        chatMediaSenderClass: Class<*>,
        writeTypeClass: Class<*>,
        listenerClass: Class<*>,
    ): Method? =
        chatMediaSenderClass.methods.firstOrNull { method ->
            method.name == "A" &&
                !java.lang.reflect.Modifier
                    .isStatic(method.modifiers) &&
                method.parameterCount == 3 &&
                method.parameterTypes[1] == writeTypeClass &&
                method.parameterTypes[2] == listenerClass
        }

    private fun injectThreadMetadata(
        sendingLog: Any,
        context: PendingContext,
    ) {
        val sendingLogClass = sendingLog.javaClass
        val scopeValue = context.threadScope
        val threadIdValue = java.lang.Long.valueOf(context.threadId)

        runCatching {
            sendingLogClass.getMethod("H1", Int::class.javaPrimitiveType).invoke(sendingLog, scopeValue)
        }.getOrElse {
            val scopeField = sendingLogClass.getDeclaredField("Z").apply { isAccessible = true }
            scopeField.setInt(sendingLog, scopeValue)
        }

        runCatching {
            sendingLogClass.getMethod("J1", java.lang.Long::class.java).invoke(sendingLog, threadIdValue)
        }.getOrElse {
            val threadField = sendingLogClass.getDeclaredField("V0").apply { isAccessible = true }
            threadField.set(sendingLog, threadIdValue)
        }
    }
}
