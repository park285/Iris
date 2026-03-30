package party.qwer.iris.config

import party.qwer.iris.ApiRequestException
import party.qwer.iris.ConfigManager
import party.qwer.iris.ConfigUpdateOutcome
import party.qwer.iris.DEFAULT_WEBHOOK_ROUTE
import party.qwer.iris.UserConfigState
import party.qwer.iris.canonicalWebhookRoute
import party.qwer.iris.model.ConfigRequest
import party.qwer.iris.model.ConfigValues

internal fun interface ConfigMutator {
    fun apply(
        configManager: ConfigManager,
        name: String,
        request: ConfigRequest,
    ): ConfigUpdateOutcome
}

internal data class ConfigValidationError(
    val field: ConfigField,
    val message: String,
)

internal object ConfigPolicy {
    private data class ConfigFieldPolicy(
        val field: ConfigField,
        val contractName: String,
        val restartRequired: Boolean,
        val differs: (ConfigValues, ConfigValues) -> Boolean,
        val validate: (UserConfigState) -> String? = { null },
    )

    private val fieldPolicies: List<ConfigFieldPolicy> =
        listOf(
            ConfigFieldPolicy(
                field = ConfigField.BOT_NAME,
                contractName = "bot_name",
                restartRequired = false,
                differs = { snapshot, effective -> snapshot.botName != effective.botName },
            ),
            ConfigFieldPolicy(
                field = ConfigField.BOT_SOCKET_PORT,
                contractName = "bot_http_port",
                restartRequired = true,
                differs = { snapshot, effective -> snapshot.botHttpPort != effective.botHttpPort },
                validate = { state ->
                    if (state.botHttpPort < 1 || state.botHttpPort > 65535) {
                        "port must be between 1 and 65535"
                    } else {
                        null
                    }
                },
            ),
            ConfigFieldPolicy(
                field = ConfigField.DB_POLLING_RATE,
                contractName = "db_polling_rate",
                restartRequired = false,
                differs = { snapshot, effective -> snapshot.dbPollingRate != effective.dbPollingRate },
                validate = { state ->
                    if (state.dbPollingRate < 1) {
                        "dbPollingRate must be greater than 0"
                    } else {
                        null
                    }
                },
            ),
            ConfigFieldPolicy(
                field = ConfigField.MESSAGE_SEND_RATE,
                contractName = "message_send_rate",
                restartRequired = false,
                differs = { snapshot, effective -> snapshot.messageSendRate != effective.messageSendRate },
                validate = { state ->
                    if (state.messageSendRate < 0) {
                        "messageSendRate must be 0 or greater"
                    } else {
                        null
                    }
                },
            ),
            ConfigFieldPolicy(
                field = ConfigField.MESSAGE_SEND_JITTER_MAX,
                contractName = "message_send_jitter_max",
                restartRequired = false,
                differs = { snapshot, effective -> snapshot.messageSendJitterMax != effective.messageSendJitterMax },
                validate = { state ->
                    if (state.messageSendJitterMax < 0) {
                        "messageSendJitterMax must be 0 or greater"
                    } else {
                        null
                    }
                },
            ),
            ConfigFieldPolicy(
                field = ConfigField.ROUTING_POLICY,
                contractName = "routing_policy",
                restartRequired = false,
                differs = { snapshot, effective ->
                    snapshot.commandRoutePrefixes != effective.commandRoutePrefixes ||
                        snapshot.imageMessageTypeRoutes != effective.imageMessageTypeRoutes
                },
            ),
            ConfigFieldPolicy(
                field = ConfigField.INBOUND_SIGNING_SECRET,
                contractName = "inbound_signing_secret",
                restartRequired = true,
                differs = { snapshot, effective -> snapshot.inboundSigningSecret != effective.inboundSigningSecret },
            ),
            ConfigFieldPolicy(
                field = ConfigField.OUTBOUND_WEBHOOK_TOKEN,
                contractName = "outbound_webhook_token",
                restartRequired = true,
                differs = { snapshot, effective -> snapshot.outboundWebhookToken != effective.outboundWebhookToken },
            ),
            ConfigFieldPolicy(
                field = ConfigField.BOT_CONTROL_TOKEN,
                contractName = "bot_control_token",
                restartRequired = true,
                differs = { snapshot, effective -> snapshot.botControlToken != effective.botControlToken },
            ),
        )

    val defaultRoutingPolicy: RoutingPolicy =
        RoutingPolicy(
            commandRoutePrefixes = emptyMap(),
            imageMessageTypeRoutes = emptyMap(),
            requiresExternalBootstrap = true,
        )

    fun requiresRestart(field: ConfigField): Boolean = fieldPolicy(field).restartRequired

    fun validate(state: UserConfigState): List<ConfigValidationError> =
        fieldPolicies.mapNotNull { policy ->
            policy.validate(state)?.let { ConfigValidationError(policy.field, it) }
        }

    fun validateField(
        field: ConfigField,
        state: UserConfigState,
    ): ConfigValidationError? = fieldPolicy(field).validate(state)?.let { ConfigValidationError(field, it) }

    fun pendingRestartFieldNames(
        snapshot: ConfigValues,
        effective: ConfigValues,
    ): List<String> =
        fieldPolicies
            .asSequence()
            .filter { it.restartRequired }
            .filter { it.differs(snapshot, effective) }
            .map { it.contractName }
            .toList()

    fun validateWebhookEndpoint(endpoint: String): String? =
        if (endpoint.isNotEmpty() && !endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            "endpoint must start with http:// or https://"
        } else {
            null
        }

    fun validateWebhookRoute(route: String): String? =
        if (route.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            null
        } else {
            "route must contain only letters, digits, '-' or '_'"
        }

    // 설정 이름 → 변경 로직 매핑 테이블
    private val endpointMutator =
        ConfigMutator { configManager, name, request ->
            val route = resolveEndpointRoute(request.route)
            val value =
                request.endpoint?.trim()
                    ?: throw ApiRequestException("missing endpoint value")
            validateWebhookEndpoint(value)?.let { throw ApiRequestException(it) }
            configManager.setWebhookEndpoint(route, value)
            ConfigUpdateOutcome(
                name = if (route == DEFAULT_WEBHOOK_ROUTE) name else "$name.$route",
                applied = true,
                requiresRestart = false,
            )
        }

    private val dbRateMutator =
        ConfigMutator { configManager, name, request ->
            val value =
                request.rate
                    ?: throw ApiRequestException("missing or invalid rate")
            requireValidState(
                field = ConfigField.DB_POLLING_RATE,
                state = configManager.snapshotUserState().copy(dbPollingRate = value),
            )
            configManager.dbPollingRate = value
            ConfigUpdateOutcome(
                name = name,
                applied = true,
                requiresRestart = requiresRestart(ConfigField.DB_POLLING_RATE),
            )
        }

    private val sendRateMutator =
        ConfigMutator { configManager, name, request ->
            val value =
                request.rate
                    ?: throw ApiRequestException("missing or invalid rate")
            requireValidState(
                field = ConfigField.MESSAGE_SEND_RATE,
                state = configManager.snapshotUserState().copy(messageSendRate = value),
            )
            configManager.messageSendRate = value
            ConfigUpdateOutcome(
                name = name,
                applied = true,
                requiresRestart = requiresRestart(ConfigField.MESSAGE_SEND_RATE),
            )
        }

    private val botPortMutator =
        ConfigMutator { configManager, name, request ->
            val value =
                request.port
                    ?: throw ApiRequestException("missing or invalid port")
            requireValidState(
                field = ConfigField.BOT_SOCKET_PORT,
                state = configManager.snapshotUserState().copy(botHttpPort = value),
            )
            configManager.botSocketPort = value
            ConfigUpdateOutcome(
                name = name,
                applied = false,
                requiresRestart = requiresRestart(ConfigField.BOT_SOCKET_PORT),
            )
        }

    private val mutationRegistry: Map<String, ConfigMutator> =
        mapOf(
            "endpoint" to endpointMutator,
            "dbrate" to dbRateMutator,
            "sendrate" to sendRateMutator,
            "botport" to botPortMutator,
        )

    fun findMutator(name: String): ConfigMutator? = mutationRegistry[name]

    private fun resolveEndpointRoute(rawRoute: String?): String {
        val normalized = canonicalWebhookRoute(rawRoute)
        if (normalized.isEmpty()) return DEFAULT_WEBHOOK_ROUTE
        validateWebhookRoute(normalized)?.let { throw ApiRequestException(it) }
        return normalized
    }

    private fun requireValidState(
        field: ConfigField,
        state: UserConfigState,
    ) {
        validateField(field, state)?.let { error ->
            throw ApiRequestException(error.message)
        }
    }

    private fun fieldPolicy(field: ConfigField): ConfigFieldPolicy = fieldPolicies.first { it.field == field }
}
