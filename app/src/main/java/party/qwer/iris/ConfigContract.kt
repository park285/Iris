package party.qwer.iris

import party.qwer.iris.model.ConfigPendingRestart
import party.qwer.iris.model.ConfigResponse
import party.qwer.iris.model.ConfigState
import party.qwer.iris.model.ConfigUpdateResponse
import party.qwer.iris.model.ConfigValues

private const val FIELD_BOT_HTTP_PORT = "bot_http_port"

internal data class ConfigContractState(
    val snapshot: ConfigState,
    val effective: ConfigState,
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
        snapshot = contractState.snapshot,
        effective = contractState.effective,
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
        snapshot = contractState.snapshot,
        effective = contractState.effective,
        pendingRestart = contractState.pendingRestart,
    )
}

internal fun buildConfigContractState(
    snapshot: ConfigValues,
    effective: ConfigValues,
): ConfigContractState {
    val pendingRestartFields = pendingRestartFieldNames(snapshot, effective)
    return ConfigContractState(
        snapshot = snapshot.toConfigState(),
        effective = effective.toConfigState(),
        pendingRestart =
            ConfigPendingRestart(
                required = pendingRestartFields.isNotEmpty(),
                fields = pendingRestartFields,
            ),
    )
}

internal fun pendingRestartFieldNames(
    snapshot: ConfigValues,
    effective: ConfigValues,
): List<String> =
    buildList {
        if (snapshot.botHttpPort != effective.botHttpPort) {
            add(FIELD_BOT_HTTP_PORT)
        }
    }

private fun ConfigValues.toConfigState(): ConfigState =
    ConfigState(
        botName = botName,
        webEndpoint = endpoint,
        botHttpPort = botHttpPort,
        dbPollingRate = dbPollingRate,
        messageSendRate = messageSendRate,
        botId = botId,
    )
