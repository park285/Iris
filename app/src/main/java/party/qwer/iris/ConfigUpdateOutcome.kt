package party.qwer.iris

import party.qwer.iris.config.ConfigPolicy
import party.qwer.iris.model.ConfigRequest

internal data class ConfigUpdateOutcome(
    val name: String,
    val applied: Boolean,
    val requiresRestart: Boolean,
)

internal fun applyConfigUpdate(
    configManager: ConfigManager,
    name: String,
    request: ConfigRequest,
): ConfigUpdateOutcome {
    val mutator =
        ConfigPolicy.findMutator(name)
            ?: throw ApiRequestException("unknown config '$name'")
    return mutator.apply(configManager, name, request)
}
