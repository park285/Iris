package party.qwer.iris.bridge

import party.qwer.iris.Replier
import party.qwer.iris.util.RedisPoolConfig
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.params.XReadGroupParams
import redis.clients.jedis.resps.StreamEntry
import redis.clients.jedis.exceptions.JedisDataException
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class RedisReplyConsumer(
    host: String,
    port: Int,
    private val notificationReferer: String,
) : Closeable {
    private val jedis = JedisPooled(RedisPoolConfig.createPoolConfig(), host, port)

    private val started = AtomicBoolean(false)
    private val thread =
        Thread(
            {
                runLoop()
            },
            "RedisReplyConsumer",
        )

    fun start() {
        if (!started.compareAndSet(false, true)) return
        thread.start()
    }

    private fun runLoop() {
        while (started.get()) {
            try {
                consumeMessages()
            } catch (e: Exception) {
                if (e is JedisDataException && e.message?.contains("NOGROUP") == true) {
                    System.err.println(
                        "[RedisReplyConsumer] NOGROUP detected, re-creating group $CONSUMER_GROUP on $STREAM_KEY",
                    )
                    try {
                        jedis.xgroupCreate(STREAM_KEY, CONSUMER_GROUP, StreamEntryID.XGROUP_LAST_ENTRY, true)
                        System.err.println(
                            "[RedisReplyConsumer] Group re-created: $CONSUMER_GROUP on $STREAM_KEY",
                        )
                    } catch (groupEx: Exception) {
                        System.err.println(
                            "[RedisReplyConsumer] Group re-create failed: ${groupEx.message}",
                        )
                        groupEx.printStackTrace()
                    }
                } else {
                    System.err.println(
                        "[RedisReplyConsumer] Fatal error, retrying in ${RETRY_DELAY_MS}ms: ${e.message}",
                    )
                    e.printStackTrace()
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        try {
            jedis.close()
        } catch (_: Exception) {
        }
    }

    private fun consumeMessages() {
        try {
            jedis.xgroupCreate(STREAM_KEY, CONSUMER_GROUP, StreamEntryID.XGROUP_LAST_ENTRY, true)
            println("[RedisReplyConsumer] Group created: $CONSUMER_GROUP on $STREAM_KEY")
        } catch (e: Exception) {
            println("[RedisReplyConsumer] Group create skipped: ${e.message}")
        }

        val params =
            XReadGroupParams
                .xReadGroupParams()
                .block(READ_BLOCK_MS)
                .count(READ_BATCH_SIZE)

        val streams = mapOf(STREAM_KEY to StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY)

        while (started.get()) {
            val result = jedis.xreadGroupAsMap(CONSUMER_GROUP, CONSUMER_NAME, params, streams) ?: emptyMap()
            val entries = result[STREAM_KEY] ?: emptyList()

            for (entry in entries) {
                try {
                    handleMessage(entry)
                } catch (e: Exception) {
                    System.err.println(
                        "[RedisReplyConsumer] Error processing entry ${entry.id}: ${e.message}",
                    )
                    e.printStackTrace()
                }
            }
        }
    }

    private fun handleMessage(entry: StreamEntry) {
        val id = entry.id

        try {
            val fields = entry.fields

            val chatIdStr = fields[FIELD_CHAT_ID]
            val text = fields[FIELD_TEXT] ?: ""
            val threadIdStr = fields[FIELD_THREAD_ID]
            val typeStr = fields[FIELD_TYPE] ?: TYPE_FINAL

            val roomId = chatIdStr?.toLongOrNull()
            if (roomId == null) {
                System.err.println(
                    "[RedisReplyConsumer] Invalid chatId format (not Long): " +
                        "chatId=$chatIdStr, text preview: ${text.take(TEXT_PREVIEW_LENGTH)}",
                )
                return
            }

            val threadId = if (threadIdStr.isNullOrBlank()) null else threadIdStr.toLongOrNull()

            when (typeStr) {
                TYPE_WAITING -> {
                    Replier.sendMessage(notificationReferer, roomId, text, threadId)
                }
                TYPE_ERROR -> {
                    val errorText = if (!text.startsWith(ERROR_PREFIX)) "$ERROR_PREFIX $text" else text
                    Replier.sendMessage(notificationReferer, roomId, errorText, threadId)
                }
                TYPE_FINAL -> {
                    Replier.sendMessage(notificationReferer, roomId, text, threadId)
                }
                else -> {
                    System.err.println(
                        "[RedisReplyConsumer] Unknown type: $typeStr, treating as final",
                    )
                    Replier.sendMessage(notificationReferer, roomId, text, threadId)
                }
            }
        } catch (e: Exception) {
            System.err.println(
                "[RedisReplyConsumer] Exception in handleMessage for $id: ${e.message}, ACKing anyway (at-most-once)",
            )
            e.printStackTrace()
        } finally {
            // at-most-once: 무조건 ACK (실패해도 pending 안 쌓이게)
            jedis.xack(STREAM_KEY, CONSUMER_GROUP, id)
        }
    }

    override fun close() {
        started.set(false)
        try {
            thread.interrupt()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val STREAM_KEY = "kakao:bot:reply"
        private const val CONSUMER_GROUP = "iris-20q-group"
        private const val CONSUMER_NAME = "iris-consumer-1"

        private const val RETRY_DELAY_MS = 5000L
        private const val READ_BLOCK_MS = 5000
        private const val READ_BATCH_SIZE = 10
        private const val TEXT_PREVIEW_LENGTH = 50

        private const val FIELD_CHAT_ID = "chatId"
        private const val FIELD_TEXT = "text"
        private const val FIELD_THREAD_ID = "threadId"
        private const val FIELD_TYPE = "type"

        private const val TYPE_WAITING = "waiting"
        private const val TYPE_ERROR = "error"
        private const val TYPE_FINAL = "final"

        private const val ERROR_PREFIX = "⚠️"
    }
}
