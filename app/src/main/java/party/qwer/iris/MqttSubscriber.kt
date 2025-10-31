package party.qwer.iris

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

class MqttSubscriber(
    private val brokerUrl: String,
    private val notificationReferer: String,
    private val replyTopicPattern: String = "iris/bot/+/reply"
) {
    private val clientId = "iris-subscriber-${UUID.randomUUID()}"
    private val persistence = MemoryPersistence()
    private var client: MqttClient? = null

    @Volatile
    private var isConnected = false

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    init {
        connect()
    }

    private fun connect() {
        try {
            println("[MqttSubscriber] Connecting to broker: $brokerUrl with clientId: $clientId")

            client = MqttClient(brokerUrl, clientId, persistence)

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                isAutomaticReconnect = true
                connectionTimeout = 10
                keepAliveInterval = 60
                maxInflight = 10
            }

            client?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    isConnected = false
                    System.err.println("[MqttSubscriber] Connection lost: ${cause?.message}")
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    println("[MqttSubscriber] Message received on topic: $topic")
                    handleMessage(topic, message)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                }
            })

            client?.connect(options)
            isConnected = true
            println("[MqttSubscriber] Connected successfully")

            subscribe()

        } catch (e: Exception) {
            isConnected = false
            System.err.println("[MqttSubscriber] Failed to connect: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun subscribe() {
        try {
            println("[MqttSubscriber] Subscribing to topic pattern: $replyTopicPattern")
            client?.subscribe(replyTopicPattern, 1)
            println("[MqttSubscriber] Subscribed successfully to: $replyTopicPattern")
        } catch (e: Exception) {
            System.err.println("[MqttSubscriber] Failed to subscribe: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleMessage(topic: String, message: MqttMessage) {
        try {
            val payload = String(message.payload, Charsets.UTF_8)
            
            val botId = topic.split("/").getOrNull(2) ?: "unknown"
            println("[MqttSubscriber] Message received from bot: $botId on topic: $topic")
            println("[MqttSubscriber] Payload (full): $payload")

            val reply = json.decodeFromString<BotReply>(payload)

            println("[MqttSubscriber] Bot=$botId, type=${reply.type}, room=${reply.room}, threadId=${reply.threadId}")

            when (reply.type.lowercase()) {
                "text" -> {
                    val chatId = reply.room.toLongOrNull()
                    if (chatId == null) {
                        System.err.println("[MqttSubscriber] Invalid room ID: ${reply.room}")
                        return
                    }

                    val threadId = reply.threadId?.toLongOrNull()

                    println("[MqttSubscriber] Sending text message to chatId=$chatId, threadId=$threadId, referer=$notificationReferer")
                    Replier.sendMessage(notificationReferer, chatId, reply.data, threadId)
                }
                else -> {
                    System.err.println("[MqttSubscriber] Unsupported message type: ${reply.type}")
                }
            }

        } catch (e: Exception) {
            System.err.println("[MqttSubscriber] Error handling message: ${e.message}")
            e.printStackTrace()
        }
    }

    fun disconnect() {
        try {
            if (client?.isConnected == true) {
                client?.unsubscribe(replyTopicPattern)
                client?.disconnect()
                println("[MqttSubscriber] Disconnected")
            }
            client?.close()
            isConnected = false
        } catch (e: Exception) {
            System.err.println("[MqttSubscriber] Error during disconnect: ${e.message}")
        }
    }

    fun isConnected(): Boolean = isConnected && client?.isConnected == true

    @Serializable
    private data class BotReply(
        val type: String,
        val room: String,
        val threadId: String? = null,
        val data: String
    )
}
