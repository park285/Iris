package party.qwer.iris.imagebridge.runtime

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

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
                ?: return
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
                !java.lang.reflect
                    .Modifier
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
            sendingLogClass.getMethod("J1", Long::class.javaObjectType).invoke(sendingLog, threadIdValue)
        }.getOrElse {
            val threadField = sendingLogClass.getDeclaredField("V0").apply { isAccessible = true }
            threadField.set(sendingLog, threadIdValue)
        }
    }
}
