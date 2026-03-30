package party.qwer.iris.http

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

internal sealed interface RequestBodyHandle : AutoCloseable {
    val sha256Hex: String
    val sizeBytes: Long

    fun openInputStream(): InputStream

    // 테스트 전용. 프로덕션 경로는 decodeJson() 사용.
    fun readUtf8Body(): String = openInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }

    @OptIn(ExperimentalSerializationApi::class)
    fun <T> decodeJson(
        json: Json,
        deserializer: DeserializationStrategy<T>,
    ): T =
        openInputStream().use { input ->
            json.decodeFromStream(deserializer, input)
        }
}

internal data class InMemoryRequestBodyHandle(
    private val bytes: ByteArray,
    override val sha256Hex: String,
) : RequestBodyHandle {
    override val sizeBytes: Long
        get() = bytes.size.toLong()

    override fun openInputStream(): InputStream = ByteArrayInputStream(bytes)

    override fun close() = Unit
}

internal data class SpillFileRequestBodyHandle(
    private val path: Path,
    override val sizeBytes: Long,
    override val sha256Hex: String,
) : RequestBodyHandle {
    override fun openInputStream(): InputStream = Files.newInputStream(path)

    override fun close() {
        Files.deleteIfExists(path)
    }
}
