package party.qwer.iris.config

import party.qwer.iris.UserConfigState

internal data class ConfigValidationError(
    val field: ConfigField,
    val message: String,
)

internal object ConfigPolicy {
    private val restartRequiredFields: Set<ConfigField> =
        setOf(
            ConfigField.BOT_SOCKET_PORT,
            ConfigField.INBOUND_SIGNING_SECRET,
            ConfigField.OUTBOUND_WEBHOOK_TOKEN,
            ConfigField.BOT_CONTROL_TOKEN,
        )

    val defaultRoutingPolicy: RoutingPolicy =
        RoutingPolicy(
            commandRoutePrefixes = emptyMap(),
            imageMessageTypeRoutes = emptyMap(),
            requiresExternalBootstrap = true,
        )

    fun requiresRestart(field: ConfigField): Boolean = field in restartRequiredFields

    fun validate(state: UserConfigState): List<ConfigValidationError> =
        buildList {
            if (state.botHttpPort < 1 || state.botHttpPort > 65535) {
                add(ConfigValidationError(ConfigField.BOT_SOCKET_PORT, "port must be between 1 and 65535"))
            }
            if (state.dbPollingRate < 1) {
                add(ConfigValidationError(ConfigField.DB_POLLING_RATE, "dbPollingRate must be greater than 0"))
            }
            if (state.messageSendRate < 0) {
                add(ConfigValidationError(ConfigField.MESSAGE_SEND_RATE, "messageSendRate must be 0 or greater"))
            }
            if (state.messageSendJitterMax < 0) {
                add(
                    ConfigValidationError(
                        ConfigField.MESSAGE_SEND_JITTER_MAX,
                        "messageSendJitterMax must be 0 or greater",
                    ),
                )
            }
        }
}
