package party.qwer.iris

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Path

internal fun loadAuthVectors(): List<AuthContractVector> =
    Json.decodeFromString(
        resolveAuthVectorsPath().toFile().readText(),
    )

internal fun resolveAuthVectorsPath(): Path {
    val direct = Path.of("tests", "contracts", "iris_auth_vectors.json")
    if (direct.toFile().isFile) {
        return direct
    }
    val repoRootRelative = Path.of("..", "tests", "contracts", "iris_auth_vectors.json")
    if (repoRootRelative.toFile().isFile) {
        return repoRootRelative
    }
    error("iris_auth_vectors.json fixture not found")
}

@Serializable
internal data class AuthContractVector(
    val name: String,
    val secret: String,
    val method: String,
    val target: String,
    val timestampMs: String,
    val nonce: String,
    val body: String,
    val bodySha256Hex: String,
    val canonicalRequest: String,
    val signature: String,
)
