package party.qwer.iris.http

import party.qwer.iris.ReplyImagePolicy

internal data class ReplyImageIngressPolicy(
    val imagePolicy: ReplyImagePolicy = ReplyImagePolicy(),
    val bufferingPolicy: RequestBodyBufferingPolicy,
) {
    companion object {
        fun fromEnv(
            env: Map<String, String> = System.getenv(),
            defaultTmpDir: String = System.getProperty("java.io.tmpdir") ?: ".",
        ): ReplyImageIngressPolicy =
            ReplyImageIngressPolicy(
                imagePolicy = ReplyImagePolicy(),
                bufferingPolicy = RequestBodyBufferingPolicy.fromEnv(env, defaultTmpDir).copy(maxInMemoryBytes = 1),
            )
    }
}
