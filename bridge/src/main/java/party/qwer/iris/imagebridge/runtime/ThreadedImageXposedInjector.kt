package party.qwer.iris.imagebridge.runtime

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

internal object ThreadedImageXposedInjector {
    private const val TAG = "IrisBridge"
    private const val REQUEST_COMPANION_CLASS = "com.kakao.talk.manager.send.ChatSendingLogRequest\$a"
    private val installed = AtomicBoolean(false)
    private val pendingContext = ThreadLocal<PendingContext?>()

    private data class PendingContext(
        val roomId: Long,
        val threadId: Long,
        val threadScope: Int,
    )

    internal data class InjectHookBinding(
        val method: Method,
        val sendingLogArgIndex: Int,
        val source: String,
    )

    fun install(registry: KakaoClassRegistry) {
        if (!installed.compareAndSet(false, true)) return
        val bindings =
            runCatching {
                selectThreadedImageInjectBindings(
                    requestCompanionClass =
                        runCatching {
                            Class.forName(
                                REQUEST_COMPANION_CLASS,
                                false,
                                registry.chatMediaSenderClass.classLoader,
                            )
                        }.getOrNull(),
                    chatMediaSenderClass = registry.chatMediaSenderClass,
                    chatRoomClass = registry.chatRoomClass,
                    writeTypeClass = registry.writeTypeClass,
                    listenerClass = registry.listenerClass,
                )
            }.onFailure { error ->
                BridgeDiscovery.markInstallError(HOOK_SEND_THREADED_INJECT, error.message ?: error.javaClass.name)
                runCatching { Log.e(TAG, "threaded image inject method resolution failed", error) }
            }.getOrNull().orEmpty()

        if (bindings.isEmpty()) {
            BridgeDiscovery.markInstallError(HOOK_SEND_THREADED_INJECT, "no threaded inject hook candidate found")
            return
        }

        var anyInstalled = false
        bindings.forEach { binding ->
            runCatching {
                XposedBridge.hookMethod(
                    binding.method,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val context = pendingContext.get() ?: return
                            val sendingLog = param.args.getOrNull(binding.sendingLogArgIndex) ?: return
                            runCatching {
                                ReplyMarkdownSendingLogAccess.writeThreadMetadata(
                                    sendingLog = sendingLog,
                                    threadId = context.threadId,
                                    threadScope = context.threadScope,
                                )
                                BridgeDiscovery.recordHook(
                                    HOOK_SEND_THREADED_INJECT,
                                    "source=${binding.source} room=${context.roomId} threadId=${context.threadId} scope=${context.threadScope}",
                                )
                            }.onFailure { error ->
                                Log.e(TAG, "thread metadata injection failed room=${context.roomId} source=${binding.source}", error)
                            }
                        }
                    },
                )
                anyInstalled = true
            }.onFailure { error ->
                runCatching { Log.e(TAG, "threaded image inject hook install failed source=${binding.source}", error) }
            }
        }

        if (anyInstalled) {
            BridgeDiscovery.markInstalled(HOOK_SEND_THREADED_INJECT)
        } else {
            BridgeDiscovery.markInstallError(HOOK_SEND_THREADED_INJECT, "threaded inject hook install failed")
        }
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

}

internal fun selectThreadedImageInjectBindingsForTest(
    requestCompanionClass: Class<*>?,
    chatMediaSenderClass: Class<*>,
    chatRoomClass: Class<*>,
    writeTypeClass: Class<*>,
    listenerClass: Class<*>,
): List<ThreadedImageXposedInjector.InjectHookBinding> =
    selectThreadedImageInjectBindings(
        requestCompanionClass = requestCompanionClass,
        chatMediaSenderClass = chatMediaSenderClass,
        chatRoomClass = chatRoomClass,
        writeTypeClass = writeTypeClass,
        listenerClass = listenerClass,
    )

internal fun selectThreadedImageInjectMethodForTest(
    chatMediaSenderClass: Class<*>,
    writeTypeClass: Class<*>,
    listenerClass: Class<*>,
): Method =
    selectLegacyThreadedImageInjectMethod(
        chatMediaSenderClass = chatMediaSenderClass,
        writeTypeClass = writeTypeClass,
        listenerClass = listenerClass,
    )

private fun selectThreadedImageInjectBindings(
    requestCompanionClass: Class<*>?,
    chatMediaSenderClass: Class<*>,
    chatRoomClass: Class<*>,
    writeTypeClass: Class<*>,
    listenerClass: Class<*>,
): List<ThreadedImageXposedInjector.InjectHookBinding> {
    val bindings = mutableListOf<ThreadedImageXposedInjector.InjectHookBinding>()
    requestCompanionClass?.let { companionClass ->
        selectReplyMarkdownRequestHookMethodForTest(
            companionClass = companionClass,
            chatRoomClass = chatRoomClass,
            writeTypeClass = writeTypeClass,
            listenerClass = listenerClass,
        )?.let { method ->
            bindings +=
                ThreadedImageXposedInjector.InjectHookBinding(
                    method = method,
                    sendingLogArgIndex = 1,
                    source = "request",
                )
        }
    }
    bindings +=
        ThreadedImageXposedInjector.InjectHookBinding(
            method =
                selectLegacyThreadedImageInjectMethod(
                    chatMediaSenderClass = chatMediaSenderClass,
                    writeTypeClass = writeTypeClass,
                    listenerClass = listenerClass,
                ),
            sendingLogArgIndex = 0,
            source = "legacy",
        )
    return bindings
}

private fun selectLegacyThreadedImageInjectMethod(
    chatMediaSenderClass: Class<*>,
    writeTypeClass: Class<*>,
    listenerClass: Class<*>,
): Method =
    KakaoClassRegistry.selectMethodCandidateForTest(
        label = "ChatMediaSender threaded inject on ${chatMediaSenderClass.name}",
        candidates =
            collectMethodsInHierarchy(chatMediaSenderClass).filter { method ->
                !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 3 &&
                    method.parameterTypes[1] == writeTypeClass &&
                    method.parameterTypes[2] == listenerClass
            },
        preferredNames = setOf("A"),
    )

private fun collectMethodsInHierarchy(clazz: Class<*>): List<Method> {
    val methods = mutableListOf<Method>()
    var current: Class<*>? = clazz
    while (current != null && current != Any::class.java) {
        current.declaredMethods.forEach { method ->
            runCatching { method.isAccessible = true }
            methods += method
        }
        current = current.superclass
    }
    return methods
}
