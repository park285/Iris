package party.qwer.iris.http

import party.qwer.iris.ReplyImagePolicy

internal data class ReplyImageIngressPolicy(
    val imagePolicy: ReplyImagePolicy = ReplyImagePolicy(),
    val bufferingPolicy: RequestBodyBufferingPolicy,
) {
    companion object {
        fun fromEnv(
            env: Map<String, String> = System.getenv(),
        ): ReplyImageIngressPolicy =
            ReplyImageIngressPolicy(
                imagePolicy = ReplyImagePolicy(),
                bufferingPolicy = RequestBodyBufferingPolicy.fromEnv(env).copy(maxInMemoryBytes = 1),
            )
    }
}
