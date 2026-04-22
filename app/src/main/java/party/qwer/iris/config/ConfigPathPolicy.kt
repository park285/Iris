package party.qwer.iris.config

import party.qwer.iris.IrisRuntimePathPolicy

internal object ConfigPathPolicy {
    private const val REQUEST_BODY_SPILL_SUBDIR = "spool/request-bodies"
    private const val VERIFIED_IMAGE_SPILL_SUBDIR = "spool/verified-image-handles"

    fun resolveConfigPath(
        env: Map<String, String> = System.getenv(),
    ): String = IrisRuntimePathPolicy.resolve(env).configPath

    fun resolveLogDirectory(
        env: Map<String, String> = System.getenv(),
    ): String = IrisRuntimePathPolicy.resolve(env).logDir

    fun resolveRequestBodySpillDirectory(
        env: Map<String, String> = System.getenv(),
    ): String = "${IrisRuntimePathPolicy.resolve(env).dataDir}/$REQUEST_BODY_SPILL_SUBDIR"

    fun resolveVerifiedImageSpillDirectory(
        env: Map<String, String> = System.getenv(),
    ): String = "${IrisRuntimePathPolicy.resolve(env).dataDir}/$VERIFIED_IMAGE_SPILL_SUBDIR"
}
