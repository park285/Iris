package party.qwer.iris.http

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplyImageIngressPolicyTest {
    @Test
    fun `fromEnv forces multipart image parts to spill while preserving spill directory`() {
        val policy =
            ReplyImageIngressPolicy.fromEnv(
                env =
                    mapOf(
                        "IRIS_REQUEST_BODY_MAX_IN_MEMORY_BYTES" to "65536",
                        "IRIS_REQUEST_BODY_SPILL_DIR" to "/tmp/iris-reply-spill",
                    ),
            )

        assertEquals(1, policy.bufferingPolicy.maxInMemoryBytes)
        assertEquals(Paths.get("/tmp/iris-reply-spill"), policy.bufferingPolicy.spillDirectory)
        assertEquals(8, policy.imagePolicy.maxImagesPerRequest)
        assertEquals(30 * 1024 * 1024, policy.imagePolicy.maxTotalBytes)
        assertEquals(64 * 1024, policy.imagePolicy.maxMetadataBytes)
        assertEquals(256 * 1024, policy.imagePolicy.jsonReplyBodyMaxBytes)
    }
}
