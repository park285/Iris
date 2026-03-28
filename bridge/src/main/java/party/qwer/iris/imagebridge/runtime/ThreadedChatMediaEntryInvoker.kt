package party.qwer.iris.imagebridge.runtime

import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Proxy

internal class ThreadedChatMediaEntryInvoker(
    private val registry: KakaoClassRegistry,
    private val pathArgumentFactory: (String) -> Any,
) {
    private val entryMethod: Method by lazy {
        registry.chatMediaSenderClass.methods.firstOrNull { method ->
            method.name == "o" &&
                method.parameterCount == 10 &&
                method.parameterTypes[0] == List::class.java &&
                method.parameterTypes[1] == registry.messageTypeClass &&
                method.parameterTypes[5] == registry.writeTypeClass &&
                method.parameterTypes[8] == registry.function1Class &&
                method.parameterTypes[9] == registry.function1Class
        } ?: error("ChatMediaSender threaded entry method not found")
    }

    fun invoke(
        sender: Any,
        imagePaths: List<String>,
    ) {
        val uris =
            ArrayList<Any>(imagePaths.size).apply {
                imagePaths.forEach { path -> add(pathArgumentFactory(path)) }
            }
        val type = if (imagePaths.size == 1) registry.photoType else registry.multiPhotoType
        val callingPkgAttachment =
            JSONObject().apply {
                put("callingPkg", "com.kakao.talk")
            }
        val loader = registry.chatMediaSenderClass.classLoader ?: error("no classLoader")
        val identityFunctionProxy =
            Proxy.newProxyInstance(loader, arrayOf(registry.function1Class)) { _, method, args ->
                when (method.name) {
                    "invoke" -> args?.getOrNull(0)
                    "toString" -> "IrisBridgeIdentityFunction"
                    "hashCode" -> 0
                    "equals" -> false
                    else -> null
                }
            }
        val noopFunctionProxy =
            Proxy.newProxyInstance(loader, arrayOf(registry.function1Class)) { _, method, _ ->
                when (method.name) {
                    "invoke" -> null
                    "toString" -> "IrisBridgeNoopFunction"
                    "hashCode" -> 0
                    "equals" -> false
                    else -> null
                }
            }
        val writeTypeConnect =
            registry.writeTypeClass.enumConstants?.firstOrNull { constant ->
                (constant as Enum<*>).name == "Connect"
            } ?: registry.writeTypeNone

        entryMethod.invoke(
            sender,
            uris,
            type,
            "",
            callingPkgAttachment,
            null,
            writeTypeConnect,
            false,
            false,
            identityFunctionProxy,
            noopFunctionProxy,
        )
    }
}
