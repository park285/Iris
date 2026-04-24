package party.qwer.iris.imagebridge.runtime

import android.util.Log
import java.lang.reflect.Modifier

internal class ChatRoomIntentMetadataResolver(
    private val resolveRoom: (Long) -> Any?,
) {
    fun resolveChatRoomType(roomId: Long): String? =
        runCatching {
            val room = resolveRoom(roomId) ?: return null
            readChatRoomType(room)
        }
            .onFailure { error ->
                Log.w(TAG, "failed to resolve chatroom type roomId=$roomId: ${error.message}")
            }
            .getOrNull()

    private fun readChatRoomType(room: Any): String? {
        val type =
            room.javaClass.methods
                .firstOrNull { method ->
                    method.name == CHAT_ROOM_TYPE_METHOD &&
                        method.parameterCount == 0 &&
                        !Modifier.isStatic(method.modifiers)
                }
                ?.invoke(room)
                ?: return null

        type.javaClass.methods
            .firstOrNull { method ->
                method.name == CHAT_ROOM_TYPE_VALUE_METHOD &&
                    method.parameterCount == 0 &&
                    method.returnType == String::class.java
            }
            ?.invoke(type)
            ?.let { value ->
                (value as? String)?.takeIf { it.isNotBlank() }?.let { return it }
            }

        type.javaClass.declaredFields
            .firstOrNull { field -> field.name == CHAT_ROOM_TYPE_VALUE_FIELD && field.type == String::class.java }
            ?.let { field ->
                field.isAccessible = true
                (field.get(type) as? String)?.takeIf { it.isNotBlank() }?.let { return it }
            }

        return (type as? Enum<*>)?.name?.takeIf { it.isNotBlank() }
    }

    private companion object {
        private const val TAG = "IrisBridge"
        private const val CHAT_ROOM_TYPE_METHOD = "y1"
        private const val CHAT_ROOM_TYPE_VALUE_METHOD = "getValue"
        private const val CHAT_ROOM_TYPE_VALUE_FIELD = "value"
    }
}
