package party.qwer.iris.bridge

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class DiscoveryHookStatus(
    val name: String,
    val installed: Boolean,
    val installError: String? = null,
    val invocationCount: Int,
    val lastSeenEpochMs: Long? = null,
    val lastSummary: String? = null,
)

internal data class BridgeDiscoverySnapshot(
    val installAttempted: Boolean,
    val hooks: List<DiscoveryHookStatus>,
)

internal fun BridgeDiscoverySnapshot.requiredSendHookName(imageCount: Int): String = if (imageCount == 1) "bh.c#n" else "bh.c#p"

internal fun BridgeDiscoverySnapshot.sendBlockReason(imageCount: Int): String? {
    if (!installAttempted) {
        return "bridge discovery hooks not installed"
    }
    val requiredHookName = requiredSendHookName(imageCount)
    val hook =
        hooks.firstOrNull { it.name == requiredHookName }
            ?: return "bridge discovery hook missing from snapshot: $requiredHookName"
    if (!hook.installed) {
        return "bridge discovery hook not ready: $requiredHookName"
    }
    return null
}

internal object BridgeDiscovery {
    private const val TAG = "IrisBridge"
    private const val HOOK_MASTER_DB_O = "MasterDatabase#O"
    private const val HOOK_MANAGER_D0 = "hp.J0#d0"
    private const val HOOK_MANAGER_E0 = "hp.J0#e0"
    private const val HOOK_MEDIA_N = "bh.c#n"
    private const val HOOK_MEDIA_P = "bh.c#p"

    private val installStarted = AtomicBoolean(false)
    private val installAttempted = AtomicBoolean(false)
    private val states =
        listOf(
            HOOK_MASTER_DB_O,
            HOOK_MANAGER_D0,
            HOOK_MANAGER_E0,
            HOOK_MEDIA_N,
            HOOK_MEDIA_P,
        ).associateWith { DiscoveryHookState() }.toMutableMap()

    fun install(classLoader: ClassLoader) {
        installAttempted.set(true)
        if (!installStarted.compareAndSet(false, true)) {
            return
        }

        installHook(HOOK_MASTER_DB_O) {
            XposedHelpers.findAndHookMethod(
                "com.kakao.talk.database.MasterDatabase",
                classLoader,
                "O",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        record(HOOK_MASTER_DB_O, "roomDao requested")
                    }
                },
            )
        }

        installHook(HOOK_MANAGER_D0) {
            XposedHelpers.findAndHookMethod(
                "hp.J0",
                classLoader,
                "d0",
                Long::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        record(HOOK_MANAGER_D0, "roomId=${param.args.getOrNull(0)}")
                    }
                },
            )
        }

        installHook(HOOK_MANAGER_E0) {
            XposedHelpers.findAndHookMethod(
                "hp.J0",
                classLoader,
                "e0",
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        record(
                            HOOK_MANAGER_E0,
                            "roomId=${param.args.getOrNull(0)} includeMembers=${param.args.getOrNull(1)} includeOpenLink=${param.args.getOrNull(2)}",
                        )
                    }
                },
            )
        }

        installHook(HOOK_MEDIA_N) {
            val mediaItemClass = Class.forName("com.kakao.talk.model.media.MediaItem", true, classLoader)
            XposedHelpers.findAndHookMethod(
                "bh.c",
                classLoader,
                "n",
                mediaItemClass,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        record(HOOK_MEDIA_N, "mediaItem=${param.args.getOrNull(0)} suppressAnimation=${param.args.getOrNull(1)}")
                    }
                },
            )
        }

        installHook(HOOK_MEDIA_P) {
            val messageTypeClass =
                runCatching { Class.forName("Op.EnumC16810c", true, classLoader) }.getOrElse {
                    Class.forName("Op.c", true, classLoader)
                }
            val writeTypeClass = Class.forName("com.kakao.talk.manager.send.ChatSendingLogRequest\$c", true, classLoader)
            val listenerClass = Class.forName("com.kakao.talk.manager.send.m", true, classLoader)
            XposedHelpers.findAndHookMethod(
                "bh.c",
                classLoader,
                "p",
                List::class.java,
                messageTypeClass,
                String::class.java,
                org.json.JSONObject::class.java,
                org.json.JSONObject::class.java,
                writeTypeClass,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                listenerClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val uriCount = (param.args.getOrNull(0) as? List<*>)?.size ?: -1
                        record(
                            HOOK_MEDIA_P,
                            "uris=$uriCount type=${param.args.getOrNull(1)} shareOriginal=${param.args.getOrNull(6)} highQuality=${param.args.getOrNull(7)}",
                        )
                    }
                },
            )
        }
    }

    fun snapshot(): BridgeDiscoverySnapshot =
        BridgeDiscoverySnapshot(
            installAttempted = installAttempted.get(),
            hooks =
                states.entries.map { (name, state) ->
                    state.toSnapshot(name)
                },
        )

    internal fun resetForTest() {
        installStarted.set(false)
        installAttempted.set(false)
        states.values.forEach { it.reset() }
    }

    internal fun recordForTest(
        hookName: String,
        summary: String,
    ) {
        record(hookName, summary)
    }

    internal fun markInstalledForTest(hookName: String) {
        installAttempted.set(true)
        stateFor(hookName).markInstalled()
    }

    private fun installHook(
        hookName: String,
        installer: () -> Unit,
    ) {
        try {
            installer()
            stateFor(hookName).markInstalled()
        } catch (error: Throwable) {
            stateFor(hookName).markInstallError(error.message ?: error.javaClass.name)
            Log.e(TAG, "discovery hook install failed: $hookName", error)
        }
    }

    private fun record(
        hookName: String,
        summary: String,
    ) {
        stateFor(hookName).record(summary)
    }

    private fun stateFor(hookName: String): DiscoveryHookState = states.computeIfAbsent(hookName) { DiscoveryHookState() }
}

private class DiscoveryHookState {
    private val installed = AtomicBoolean(false)
    private val installError = AtomicReference<String?>(null)
    private val invocationCount = AtomicInteger(0)
    private val lastSeenEpochMs = AtomicLong(0L)
    private val lastSummary = AtomicReference<String?>(null)

    fun markInstalled() {
        installed.set(true)
        installError.set(null)
    }

    fun markInstallError(detail: String) {
        installed.set(false)
        installError.set(detail)
    }

    fun record(summary: String) {
        invocationCount.incrementAndGet()
        lastSeenEpochMs.set(System.currentTimeMillis())
        lastSummary.set(summary)
    }

    fun toSnapshot(name: String): DiscoveryHookStatus =
        DiscoveryHookStatus(
            name = name,
            installed = installed.get(),
            installError = installError.get(),
            invocationCount = invocationCount.get(),
            lastSeenEpochMs = lastSeenEpochMs.get().takeIf { it > 0L },
            lastSummary = lastSummary.get(),
        )

    fun reset() {
        installed.set(false)
        installError.set(null)
        invocationCount.set(0)
        lastSeenEpochMs.set(0L)
        lastSummary.set(null)
    }
}
