package party.qwer.iris

import party.qwer.iris.config.ConfigPolicy
import party.qwer.iris.model.ConfigDiscoveredState
import party.qwer.iris.model.ConfigPendingRestart
import party.qwer.iris.model.ConfigResponse
import party.qwer.iris.model.ConfigState
import party.qwer.iris.model.ConfigUpdateResponse
import party.qwer.iris.model.ConfigValues

internal data class ConfigContractState(
    val user: ConfigState,
    val applied: ConfigState,
    val discovered: ConfigDiscoveredState,
    val pendingRestart: ConfigPendingRestart,
)

internal data class ConfigUpdateStatus(
    val name: String,
    val persisted: Boolean,
    val applied: Boolean,
    val requiresRestart: Boolean,
)

internal fun buildConfigResponse(
    snapshot: ConfigValues,
    effective: ConfigValues,
): ConfigResponse {
    val contractState = buildConfigContractState(snapshot, effective)
    return ConfigResponse(
        user = contractState.user,
        applied = contractState.applied,
        discovered = contractState.discovered,
        pendingRestart = contractState.pendingRestart,
    )
}

internal fun buildConfigUpdateResponse(
    status: ConfigUpdateStatus,
    snapshot: ConfigValues,
    effective: ConfigValues,
): ConfigUpdateResponse {
    val contractState = buildConfigContractState(snapshot, effective)
    return ConfigUpdateResponse(
        name = status.name,
        persisted = status.persisted,
        applied = status.applied,
        requiresRestart = status.requiresRestart,
        user = contractState.user,
        runtimeApplied = contractState.applied,
        discovered = contractState.discovered,
        pendingRestart = contractState.pendingRestart,
    )
}

internal fun buildConfigContractState(
    snapshot: ConfigValues,
    effective: ConfigValues,
): ConfigContractState {
    val pendingRestartFields = ConfigPolicy.pendingRestartFieldNames(snapshot, effective)
    return ConfigContractState(
        user = snapshot.toConfigState(),
        applied = effective.toConfigState(),
        discovered = ConfigDiscoveredState(botId = effective.botId),
        pendingRestart =
            ConfigPendingRestart(
                required = pendingRestartFields.isNotEmpty(),
                fields = pendingRestartFields,
            ),
    )
}

private fun ConfigValues.toConfigState(): ConfigState =
    ConfigState(
        botName = botName,
        webEndpoint = endpoint,
        webhooks = webhooks,
        botHttpPort = botHttpPort,
        dbPollingRate = dbPollingRate,
        messageSendRate = messageSendRate,
        commandRoutePrefixes = commandRoutePrefixes,
        imageMessageTypeRoutes = imageMessageTypeRoutes,
        eventTypeRoutes = eventTypeRoutes,
    )
