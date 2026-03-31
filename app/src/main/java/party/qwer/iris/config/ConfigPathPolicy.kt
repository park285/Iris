package party.qwer.iris.config

import party.qwer.iris.IrisRuntimePathPolicy

internal object ConfigPathPolicy {
    fun resolveConfigPath(
        env: Map<String, String> = System.getenv(),
    ): String = IrisRuntimePathPolicy.resolve(env).configPath

    fun resolveLogDirectory(
        env: Map<String, String> = System.getenv(),
    ): String = IrisRuntimePathPolicy.resolve(env).logDir
}
