package party.qwer.iris.imagebridge.runtime

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
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

internal fun BridgeDiscoverySnapshot.requiredSendHookName(imageCount: Int): String = if (imageCount == 1) HOOK_SEND_SINGLE else HOOK_SEND_MULTIPLE

internal fun BridgeDiscoverySnapshot.sendBlockReason(imageCount: Int): String? {
    if (!installAttempted) return "bridge discovery hooks not installed"
    val requiredHookName = requiredSendHookName(imageCount)
    val hook =
        hooks.firstOrNull { it.name == requiredHookName }
            ?: return "bridge discovery hook missing from snapshot: $requiredHookName"
    if (!hook.installed) return "bridge discovery hook not ready: $requiredHookName"
    return null
}

private const val HOOK_ROOM_DAO = "MasterDatabase#roomDao"
private const val HOOK_MANAGER_DIRECT = "ChatRoomManager#directResolve"
private const val HOOK_MANAGER_BROAD = "ChatRoomManager#broadResolve"
internal const val HOOK_REPLY_MARKDOWN_INGRESS = "ReplyMarkdown#ingress"
internal const val HOOK_REPLY_MARKDOWN_REUSE = "ReplyMarkdown#reuseIntent"
internal const val HOOK_REPLY_MARKDOWN_REQUEST = "ReplyMarkdown#requestDispatch"
internal const val HOOK_SEND_SINGLE = "ChatMediaSender#sendSingle"
internal const val HOOK_SEND_MULTIPLE = "ChatMediaSender#sendMultiple"

internal object BridgeDiscovery {
    private const val TAG = "IrisBridge"

    private val installStarted = AtomicBoolean(false)
    private val installAttempted = AtomicBoolean(false)
    private val states =
        listOf(
            HOOK_ROOM_DAO,
            HOOK_MANAGER_DIRECT,
            HOOK_MANAGER_BROAD,
            HOOK_REPLY_MARKDOWN_INGRESS,
            HOOK_REPLY_MARKDOWN_REUSE,
            HOOK_REPLY_MARKDOWN_REQUEST,
            HOOK_SEND_SINGLE,
            HOOK_SEND_MULTIPLE,
        ).associateWith { DiscoveryHookState() }.toMutableMap()

    fun install(registry: KakaoClassRegistry) {
        installAttempted.set(true)
        if (!installStarted.compareAndSet(false, true)) return

        installMethodHook(HOOK_ROOM_DAO, registry.roomDaoMethod) {
            "roomDao requested"
        }

        installMethodHook(HOOK_MANAGER_DIRECT, registry.directRoomResolverMethod) { param ->
            "roomId=${param.args.getOrNull(0)}"
        }

        installMethodHook(HOOK_MANAGER_BROAD, registry.broadRoomResolverMethod) { param ->
            "roomId=${param.args.getOrNull(0)} includeMembers=${param.args.getOrNull(1)} includeOpenLink=${param.args.getOrNull(2)}"
        }

        installMethodHook(HOOK_SEND_SINGLE, registry.singleSendMethod) { param ->
            "mediaItem=${param.args.getOrNull(0)} suppressAnimation=${param.args.getOrNull(1)}"
        }

        installMethodHook(HOOK_SEND_MULTIPLE, registry.multiSendMethod) { param ->
            val uriCount = (param.args.getOrNull(0) as? List<*>)?.size ?: -1
            "uris=$uriCount type=${param.args.getOrNull(1)} shareOriginal=${param.args.getOrNull(6)} highQuality=${param.args.getOrNull(7)}"
        }
    }

    fun snapshot(): BridgeDiscoverySnapshot =
        BridgeDiscoverySnapshot(
            installAttempted = installAttempted.get(),
            hooks = states.entries.map { (name, state) -> state.toSnapshot(name) },
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

    internal fun markInstalled(hookName: String) {
        installAttempted.set(true)
        stateFor(hookName).markInstalled()
    }

    internal fun markInstallError(
        hookName: String,
        detail: String,
    ) {
        installAttempted.set(true)
        stateFor(hookName).markInstallError(detail)
    }

    internal fun recordHook(
        hookName: String,
        summary: String,
    ) {
        installAttempted.set(true)
        record(hookName, summary)
    }

    private fun installMethodHook(
        hookName: String,
        method: Method,
        summarize: (XC_MethodHook.MethodHookParam) -> String,
    ) {
        if (Modifier.isAbstract(method.modifiers)) {
            stateFor(hookName).markInstallError("abstract method, skipped: ${method.declaringClass.name}.${method.name}")
            Log.w(TAG, "discovery hook skipped (abstract): $hookName — ${method.declaringClass.name}.${method.name}")
            return
        }
        try {
            XposedBridge.hookMethod(
                method,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        record(hookName, summarize(param))
                    }
                },
            )
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
