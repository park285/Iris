package party.qwer.iris

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import party.qwer.iris.model.BotRoute
import java.util.UUID

class MqttPublisher(
    private val brokerUrl: String,
    private val routes: List<BotRoute>
) {
    private val clientId = "iris-publisher-${UUID.randomUUID()}"
    private val persistence = MemoryPersistence()
    private var client: MqttClient? = null
    
    @Volatile
    private var isConnected = false

    init {
        connect()
    }

    private fun connect() {
        try {
            println("[MqttPublisher] Connecting to broker: $brokerUrl with clientId: $clientId")
            
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
                    System.err.println("[MqttPublisher] Connection lost: ${cause?.message}")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    println("[MqttPublisher] Message delivered: topic=${token?.topics?.joinToString()}")
                }
            })

            client?.connect(options)
            isConnected = true
            println("[MqttPublisher] Connected successfully")
            
        } catch (e: Exception) {
            isConnected = false
            System.err.println("[MqttPublisher] Failed to connect: ${e.message}")
            e.printStackTrace()
        }
    }
    suspend fun route(message: String, jsonData: String): Boolean = withContext(Dispatchers.IO) {
        if (message.isBlank()) {
            println("[MqttPublisher] Empty message, skipping routing")
            return@withContext false
        }

        if (!ensureConnected()) {
            System.err.println("[MqttPublisher] Not connected to broker")
            return@withContext false
        }

        val matchedRoute = routes
            .filter { route -> route.enabled && message.startsWith(route.prefix, ignoreCase = true) }
            .maxByOrNull { it.prefix.length }

        if (matchedRoute == null) {
            println("[MqttPublisher] No matching routes for message: ${message.take(50)}")
            return@withContext false
        }

        return@withContext publish(matchedRoute.mqttTopic, jsonData)
    }

    private fun publish(topic: String, payload: String): Boolean {
        if (!ensureConnected()) {
            System.err.println("[MqttPublisher] Cannot publish - not connected")
            return false
        }

        return try {
            println("[MqttPublisher] Publishing to topic: $topic")
            
            val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                qos = 1 
                isRetained = false 
            }

            client?.publish(topic, message)
            println("[MqttPublisher] Published successfully to $topic")
            true
            
        } catch (e: MqttException) {
            System.err.println("[MqttPublisher] Failed to publish to $topic: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    private fun ensureConnected(): Boolean {
        if (isConnected && client?.isConnected == true) {
            return true
        }

        println("[MqttPublisher] Attempting to reconnect...")
        connect()
        return isConnected && client?.isConnected == true
    }

    fun disconnect() {
        try {
            if (client?.isConnected == true) {
                client?.disconnect()
                println("[MqttPublisher] Disconnected")
            }
            client?.close()
            isConnected = false
        } catch (e: Exception) {
            System.err.println("[MqttPublisher] Error during disconnect: ${e.message}")
        }
    }

    fun isConnected(): Boolean = isConnected && client?.isConnected == true
}
