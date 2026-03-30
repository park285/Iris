package party.qwer.iris.http

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

internal class RequestBodyHandle(
    private val storage: RequestBodyStorage,
    val sha256Hex: String,
) : AutoCloseable {
    // 테스트 전용. 프로덕션 경로는 decodeJson() 사용.
    internal fun readUtf8Body(): String = storage.readUtf8Body()

    @OptIn(ExperimentalSerializationApi::class)
    fun <T> decodeJson(
        json: Json,
        deserializer: DeserializationStrategy<T>,
    ): T =
        storage.openInputStream().use { input ->
            json.decodeFromStream(deserializer, input)
        }

    override fun close() {
        storage.close()
    }
}
