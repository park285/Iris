package party.qwer.iris.bridge

import party.qwer.iris.Configurable
import party.qwer.iris.IrisLogger
import party.qwer.iris.util.RedisConnectionManager
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.StreamEntryID
import java.io.Closeable

class RedisProducer : Closeable {
    private val jedis: JedisPooled = RedisConnectionManager.getPool()

    fun route(
        text: String?,
        room: String,
        sender: String,
        userId: String,
        threadId: String? = null,
    ): Boolean {
        val msg =
            text ?: run {
                IrisLogger.error("[RedisProducer] route(): text is null, ignoring")
                return true
            }
        val targetStream =
            when {
                msg.startsWith(PREFIX_COMMENT) -> return true
                msg.startsWith(PREFIX_TURTLE_SOUP) -> STREAM_TURTLE_SOUP
                msg.startsWith(PREFIX_ASSISTANT) -> STREAM_ASSISTANT
                msg.startsWith(PREFIX_GENERIC) -> STREAM_HOLOLIVE
                msg.startsWith(PREFIX_20Q) -> STREAM_20Q
                Configurable.isAssistantFullRoom(room) -> STREAM_ASSISTANT
                else -> return true
            }

        return try {
            jedis.xadd(
                targetStream,
                StreamEntryID.NEW_ENTRY,
                mapOf(
                    FIELD_TEXT to msg,
                    FIELD_ROOM to room,
                    FIELD_SENDER to sender,
                    FIELD_USER_ID to userId,
                    FIELD_THREAD_ID to (threadId ?: ""),
                ),
            )
            true
        } catch (e: Exception) {
            IrisLogger.error("[RedisProducer] Exception in route(): ${e.message}")
            false
        }
    }

    override fun close() {
        // 공유 풀 사용 - RedisConnectionManager에서 lifecycle 관리
    }

    companion object {
        private const val FIELD_TEXT = "text"
        private const val FIELD_ROOM = "room"
        private const val FIELD_SENDER = "sender"
        private const val FIELD_USER_ID = "userId"
        private const val FIELD_THREAD_ID = "threadId"
        private const val STREAM_TURTLE_SOUP = "kakao:turtle-soup"
        private const val STREAM_HOLOLIVE = "kakao:hololive"
        private const val STREAM_20Q = "kakao:20q"
        private const val STREAM_ASSISTANT = "kakao:assistant"
        private const val PREFIX_COMMENT = "//"
        private const val PREFIX_TURTLE_SOUP = "/스프"
        private const val PREFIX_GENERIC = "!"
        private const val PREFIX_20Q = "/스자"
        private const val PREFIX_ASSISTANT = "/어시"
    }
}
