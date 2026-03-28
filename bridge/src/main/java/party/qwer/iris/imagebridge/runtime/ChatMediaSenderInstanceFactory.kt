@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package party.qwer.iris.imagebridge.runtime

import org.json.JSONObject
import java.lang.reflect.Constructor
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

internal class ChatMediaSenderInstanceFactory(
    private val registry: KakaoClassRegistry,
) {
    private val senderConstructorCache = ConcurrentHashMap<Class<*>, Constructor<*>>()

    fun newSender(
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
        val exactCandidates = candidates.filter { constructor -> constructor.parameterTypes[0] == chatRoomClass }
        if (exactCandidates.size == 1) {
            return exactCandidates.single()
        }
        if (exactCandidates.size > 1) {
            val signatures =
                exactCandidates.joinToString { constructor ->
                    constructor.parameterTypes.joinToString(
                        prefix = "${constructor.declaringClass.name}(",
                        postfix = ")",
                    ) { parameterType -> parameterType.name }
                }
            error("ChatMediaSender exact constructor is ambiguous for chatRoom=${chatRoomClass.name}: $signatures")
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
