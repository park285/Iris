package party.qwer.iris

import party.qwer.iris.config.ConfigPolicy
import party.qwer.iris.model.ConfigRequest
import party.qwer.iris.model.ConfigUpdateResponse

internal data class ConfigUpdateOutcome(
    val name: String,
    val persisted: Boolean,
    val applied: Boolean,
    val requiresRestart: Boolean,
    val response: ConfigUpdateResponse?,
)

internal data class ConfigMutationPlan(
    val candidateSnapshot: UserConfigState,
    val applyImmediately: Boolean,
)

internal data class PlannedConfigUpdate(
    val name: String,
    val applied: Boolean,
    val requiresRestart: Boolean,
    val plan: ConfigMutationPlan,
)

internal fun applyConfigUpdate(
    configManager: ConfigManager,
    name: String,
    request: ConfigRequest,
): ConfigUpdateOutcome {
    val mutator =
        ConfigPolicy.findMutator(name)
            ?: throw ApiRequestException("unknown config '$name'")
    return configManager.applyConfigMutation { snapshotUser ->
        mutator.apply(snapshotUser, name, request)
    }
}
