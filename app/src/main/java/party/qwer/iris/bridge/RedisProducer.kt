package party.qwer.iris.bridge

import org.json.JSONObject
import party.qwer.iris.util.RedisPoolConfig
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.StreamEntryID
import java.io.Closeable

class RedisProducer(
    host: String,
    port: Int,
) : Closeable {
    private val jedis = JedisPooled(RedisPoolConfig.createPoolConfig(), host, port)

    fun route(
        text: String?,
        dataJson: String,
    ): Boolean {
        val msg = text ?: run {
            System.err.println("[RedisProducer] route(): text is null, ignoring")
            return true
        }
        val targetStream =
            when {
                msg.startsWith(PREFIX_COMMENT) -> return true
                msg.startsWith(PREFIX_CHESS) -> STREAM_CHESS
                msg.startsWith(PREFIX_GENERIC) -> STREAM_HOLOLIVE
                msg.startsWith(PREFIX_20Q) -> STREAM_20Q
                else -> return true
            }

        return try {
            val root = JSONObject(dataJson)
            jedis.xadd(
                targetStream,
                StreamEntryID.NEW_ENTRY,
                mapOf(
                    "text" to msg,
                    "room" to root.optString(FIELD_ROOM, ""),
                    "sender" to root.optString(FIELD_SENDER, ""),
                    "threadId" to "",
                    "rawJson" to dataJson,
                ),
            )
            true
        } catch (e: Exception) {
            System.err.println("[RedisProducer] Exception in route(): ${e.message}")
            false
        }
    }

    override fun close() {
        try {
            jedis.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val FIELD_ROOM = "room"
        private const val FIELD_SENDER = "sender"
        private const val STREAM_CHESS = "kakao:chess"
        private const val STREAM_HOLOLIVE = "kakao:hololive"
        private const val STREAM_20Q = "kakao:20q"
        private const val PREFIX_COMMENT = "//"
        private const val PREFIX_CHESS = "!체스"
        private const val PREFIX_GENERIC = "!"
        private const val PREFIX_20Q = "/스자"
    }
}
