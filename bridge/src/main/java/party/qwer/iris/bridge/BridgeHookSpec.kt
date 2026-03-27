package party.qwer.iris.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier

internal data class BridgeSpecCheck(
    val name: String,
    val ok: Boolean,
    val detail: String? = null,
)

internal data class BridgeSpecStatus(
    val ready: Boolean,
    val checkedAtEpochMs: Long,
    val checks: List<BridgeSpecCheck>,
)

internal data class ImageBridgeHealthSnapshot(
    val running: Boolean,
    val specStatus: BridgeSpecStatus,
    val discoverySnapshot: BridgeDiscoverySnapshot,
    val restartCount: Int,
    val lastCrashMessage: String?,
)

internal class BridgeHookSpecVerifier(
    private val classLookup: (String) -> Class<*>,
) {
    fun verify(): BridgeSpecStatus {
        val checks = mutableListOf<BridgeSpecCheck>()
        val masterDatabaseClass = checkClass(checks, "class MasterDatabase", "com.kakao.talk.database.MasterDatabase")
        val chatRoomClass = checkClass(checks, "class hp.t", "hp.t")
        val managerClass = checkClass(checks, "class hp.J0", "hp.J0")
        val mediaSenderClass = checkClass(checks, "class bh.c", "bh.c")
        val mediaItemClass = checkClass(checks, "class MediaItem", "com.kakao.talk.model.media.MediaItem")
        val function0Class = checkClass(checks, "class Function0", "kotlin.jvm.functions.Function0")
        val function1Class = checkClass(checks, "class Function1", "kotlin.jvm.functions.Function1")
        val writeTypeClass = checkClass(checks, "class ChatSendingLogRequest\$c", "com.kakao.talk.manager.send.ChatSendingLogRequest\$c")
        val listenerClass = checkClass(checks, "class listener", "com.kakao.talk.manager.send.m")
        val messageTypeClass =
            try {
                classLookup("Op.EnumC16810c").also {
                    checks += BridgeSpecCheck("class Op.EnumC16810c", ok = true)
                }
            } catch (_: Throwable) {
                checkClass(checks, "class Op.c", "Op.c")
            }

        masterDatabaseClass?.let { klass ->
            checks +=
                verify("MasterDatabase singleton field") {
                    klass.declaredFields.firstOrNull { field ->
                        Modifier.isStatic(field.modifiers) && field.type == klass
                    } ?: error("singleton field not found")
                }
            checks +=
                verify("MasterDatabase.O()") {
                    klass.getMethod("O")
                }
        }

        managerClass?.let { klass ->
            checks +=
                verify("hp.J0 accessor") {
                    klass.methods.firstOrNull { method ->
                        method.name == "j" && method.parameterCount == 0 && method.returnType == klass
                    } ?: klass.methods.firstOrNull { method ->
                        Modifier.isStatic(method.modifiers) && method.parameterCount == 0 && method.returnType == klass
                    } ?: error("manager accessor not found")
                }
        }

        chatRoomClass?.let { klass ->
            checks +=
                verify("hp.t has resolver surface") {
                    val hasCompanionResolver =
                        klass.declaredFields.any { field ->
                            Modifier.isStatic(field.modifiers)
                        }
                    val hasSingleArgConstructor =
                        klass.declaredConstructors.any { constructor ->
                            constructor.parameterTypes.size == 1
                        }
                    check(hasCompanionResolver || hasSingleArgConstructor) { "chat room resolver surface missing" }
                }
        }

        if (mediaSenderClass != null && mediaItemClass != null) {
            checks +=
                verify("MediaItem(String,long)") {
                    mediaItemClass.getConstructor(String::class.java, Long::class.javaPrimitiveType)
                }
            checks +=
                verify("ChatMediaSender.n(...)") {
                    mediaSenderClass.getMethod("n", mediaItemClass, Boolean::class.javaPrimitiveType)
                }
        }

        if (mediaSenderClass != null && messageTypeClass != null && writeTypeClass != null && listenerClass != null) {
            checks +=
                verify("ChatMediaSender.p(...)") {
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
        }

        if (mediaSenderClass != null && function0Class != null && function1Class != null) {
            checks +=
                verify("ChatMediaSender ctor") {
                    mediaSenderClass.declaredConstructors.firstOrNull { constructor ->
                        constructor.parameterTypes.size == 4 &&
                            constructor.parameterTypes[1] == java.lang.Long::class.java &&
                            constructor.parameterTypes[2] == function0Class &&
                            constructor.parameterTypes[3] == function1Class
                    } ?: error("sender constructor not found")
                }
        }

        messageTypeClass?.let { klass ->
            checks +=
                verify("message type Photo") { requireEnumConstant(klass, "Photo") }
            checks +=
                verify("message type MultiPhoto") { requireEnumConstant(klass, "MultiPhoto") }
        }
        writeTypeClass?.let { klass ->
            checks +=
                verify("write type None") { requireEnumConstant(klass, "None") }
        }

        return BridgeSpecStatus(
            ready = checks.all { it.ok },
            checkedAtEpochMs = System.currentTimeMillis(),
            checks = checks,
        )
    }

    private fun checkClass(
        checks: MutableList<BridgeSpecCheck>,
        name: String,
        className: String,
    ): Class<*>? =
        try {
            classLookup(className).also {
                checks += BridgeSpecCheck(name, ok = true)
            }
        } catch (error: Throwable) {
            checks += BridgeSpecCheck(name, ok = false, detail = error.message ?: error.javaClass.name)
            null
        }

    private fun verify(
        name: String,
        block: () -> Any,
    ): BridgeSpecCheck =
        try {
            block()
            BridgeSpecCheck(name, ok = true)
        } catch (error: Throwable) {
            BridgeSpecCheck(name, ok = false, detail = error.message ?: error.javaClass.name)
        }

    private fun requireEnumConstant(
        enumClass: Class<*>,
        name: String,
    ): Any =
        enumClass.enumConstants?.firstOrNull { constant ->
            (constant as Enum<*>).name == name
        } ?: error("enum constant $name not found")
}

internal fun ImageBridgeHealthSnapshot.toJson(): JSONObject =
    JSONObject().apply {
        put("status", party.qwer.iris.ImageBridgeProtocol.STATUS_OK)
        put("running", running)
        put("specReady", specStatus.ready)
        put("checkedAtEpochMs", specStatus.checkedAtEpochMs)
        put("restartCount", restartCount)
        if (!lastCrashMessage.isNullOrBlank()) {
            put("lastCrashMessage", lastCrashMessage)
        }
        put(
            "checks",
            JSONArray(
                specStatus.checks.map { check ->
                    JSONObject().apply {
                        put("name", check.name)
                        put("ok", check.ok)
                        if (!check.detail.isNullOrBlank()) {
                            put("detail", check.detail)
                        }
                    }
                },
            ),
        )
        put(
            "discovery",
            JSONObject().apply {
                put("installAttempted", discoverySnapshot.installAttempted)
                put(
                    "hooks",
                    JSONArray(
                        discoverySnapshot.hooks.map { hook ->
                            JSONObject().apply {
                                put("name", hook.name)
                                put("installed", hook.installed)
                                if (!hook.installError.isNullOrBlank()) {
                                    put("installError", hook.installError)
                                }
                                put("invocationCount", hook.invocationCount)
                                if (hook.lastSeenEpochMs != null) {
                                    put("lastSeenEpochMs", hook.lastSeenEpochMs)
                                }
                                if (!hook.lastSummary.isNullOrBlank()) {
                                    put("lastSummary", hook.lastSummary)
                                }
                            }
                        },
                    ),
                )
            },
        )
    }
