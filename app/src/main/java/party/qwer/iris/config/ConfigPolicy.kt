package party.qwer.iris.config

import party.qwer.iris.ApiRequestException
import party.qwer.iris.ConfigMutationPlan
import party.qwer.iris.DEFAULT_WEBHOOK_ROUTE
import party.qwer.iris.PlannedConfigUpdate
import party.qwer.iris.UserConfigState
import party.qwer.iris.canonicalWebhookRoute
import party.qwer.iris.model.ConfigRequest
import party.qwer.iris.model.ConfigValues
import party.qwer.iris.toLegacyConfigValues
import party.qwer.iris.toUserConfigState
import party.qwer.iris.updateWebhookConfig

internal fun interface ConfigMutator {
    fun apply(
        snapshotUser: UserConfigState,
        name: String,
        request: ConfigRequest,
    ): PlannedConfigUpdate
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
            ConfigFieldPolicy(
                field = ConfigField.BRIDGE_TOKEN,
                contractName = "bridge_token",
                restartRequired = true,
                differs = { snapshot, effective -> snapshot.bridgeToken != effective.bridgeToken },
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
        buildList {
            fieldPolicies.mapNotNullTo(this) { policy ->
                policy.validate(state)?.let { ConfigValidationError(policy.field, it) }
            }

            validateWebhookEndpoint(state.endpoint.trim())?.let { message ->
                add(ConfigValidationError(ConfigField.ROUTING_POLICY, "endpoint: $message"))
            }

            state.webhooks.forEach { (route, endpoint) ->
                validateRouteMapEntry(
                    rawRoute = route,
                    fieldPath = "webhooks.$route",
                )?.let { message ->
                    add(ConfigValidationError(ConfigField.ROUTING_POLICY, message))
                }
                validateWebhookEndpoint(endpoint.trim())?.let { message ->
                    add(ConfigValidationError(ConfigField.ROUTING_POLICY, "webhooks.$route: $message"))
                }
            }

            state.commandRoutePrefixes.forEach { (route, prefixes) ->
                validateRouteMapEntry(
                    rawRoute = route,
                    fieldPath = "commandRoutePrefixes.$route",
                )?.let { message ->
                    add(ConfigValidationError(ConfigField.ROUTING_POLICY, message))
                }
                validateNonBlankEntries(
                    fieldPath = "commandRoutePrefixes.$route",
                    values = prefixes,
                    valueName = "prefix",
                )?.let { message ->
                    add(ConfigValidationError(ConfigField.ROUTING_POLICY, message))
                }
            }

            state.imageMessageTypeRoutes.forEach { (route, messageTypes) ->
                validateRouteMapEntry(
                    rawRoute = route,
                    fieldPath = "imageMessageTypeRoutes.$route",
                )?.let { message ->
                    add(ConfigValidationError(ConfigField.ROUTING_POLICY, message))
                }
                validateNonBlankEntries(
                    fieldPath = "imageMessageTypeRoutes.$route",
                    values = messageTypes,
                    valueName = "message type",
                )?.let { message ->
                    add(ConfigValidationError(ConfigField.ROUTING_POLICY, message))
                }
            }

            validateSecret("inboundSigningSecret", state.inboundSigningSecret)?.let { message ->
                add(ConfigValidationError(ConfigField.INBOUND_SIGNING_SECRET, message))
            }
            validateSecret("outboundWebhookToken", state.outboundWebhookToken)?.let { message ->
                add(ConfigValidationError(ConfigField.OUTBOUND_WEBHOOK_TOKEN, message))
            }
            validateSecret("botControlToken", state.botControlToken)?.let { message ->
                add(ConfigValidationError(ConfigField.BOT_CONTROL_TOKEN, message))
            }
            validateSecret("bridgeToken", state.bridgeToken)?.let { message ->
                add(ConfigValidationError(ConfigField.BRIDGE_TOKEN, message))
            }
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
        if (route.isEmpty()) {
            "route must not be empty"
        } else if (route.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            null
        } else {
            "route must contain only letters, digits, '-' or '_'"
        }

    private val endpointMutator =
        ConfigMutator { snapshotUser, name, request ->
            val route = resolveEndpointRoute(request.route)
            val value =
                request.endpoint?.trim()
                    ?: throw ApiRequestException("missing endpoint value")
            validateWebhookEndpoint(value)?.let { throw ApiRequestException(it) }
            val candidateState =
                updateWebhookConfig(
                    snapshotUser.toLegacyConfigValues(),
                    route,
                    value,
                ).toUserConfigState()
            requireValidState(candidateState)
            PlannedConfigUpdate(
                name = if (route == DEFAULT_WEBHOOK_ROUTE) name else "$name.$route",
                applied = true,
                requiresRestart = false,
                plan =
                    ConfigMutationPlan(
                    candidateSnapshot = candidateState,
                    applyImmediately = true,
                ),
            )
        }

    private val dbRateMutator =
        ConfigMutator { snapshotUser, name, request ->
            val value =
                request.rate
                    ?: throw ApiRequestException("missing or invalid rate")
            val candidateState = snapshotUser.copy(dbPollingRate = value)
            requireValidState(candidateState)
            PlannedConfigUpdate(
                name = name,
                applied = true,
                requiresRestart = requiresRestart(ConfigField.DB_POLLING_RATE),
                plan =
                    ConfigMutationPlan(
                    candidateSnapshot = candidateState,
                    applyImmediately = true,
                ),
            )
        }

    private val sendRateMutator =
        ConfigMutator { snapshotUser, name, request ->
            val value =
                request.rate
                    ?: throw ApiRequestException("missing or invalid rate")
            val candidateState = snapshotUser.copy(messageSendRate = value)
            requireValidState(candidateState)
            PlannedConfigUpdate(
                name = name,
                applied = true,
                requiresRestart = requiresRestart(ConfigField.MESSAGE_SEND_RATE),
                plan =
                    ConfigMutationPlan(
                    candidateSnapshot = candidateState,
                    applyImmediately = true,
                ),
            )
        }

    private val botPortMutator =
        ConfigMutator { snapshotUser, name, request ->
            val value =
                request.port
                    ?: throw ApiRequestException("missing or invalid port")
            val candidateState = snapshotUser.copy(botHttpPort = value)
            requireValidState(candidateState)
            PlannedConfigUpdate(
                name = name,
                applied = false,
                requiresRestart = requiresRestart(ConfigField.BOT_SOCKET_PORT),
                plan =
                    ConfigMutationPlan(
                    candidateSnapshot = candidateState,
                    applyImmediately = false,
                ),
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

    private fun requireValidState(state: UserConfigState) {
        validate(state).firstOrNull()?.let { error ->
            throw ApiRequestException(error.message)
        }
    }

    private fun fieldPolicy(field: ConfigField): ConfigFieldPolicy = fieldPolicies.first { it.field == field }

    private fun validateRouteMapEntry(
        rawRoute: String,
        fieldPath: String,
    ): String? =
        validateWebhookRoute(canonicalWebhookRoute(rawRoute))?.let { message ->
            "$fieldPath: $message"
        }

    private fun validateNonBlankEntries(
        fieldPath: String,
        values: List<String>,
        valueName: String,
    ): String? =
        if (values.any { it.trim().isEmpty() }) {
            "$fieldPath: $valueName must not be blank"
        } else {
            null
        }

    private fun validateSecret(
        name: String,
        value: String,
    ): String? {
        if (value.isEmpty()) return null
        if (value != value.trim()) return "$name must not have leading or trailing whitespace"
        if (value.any { it.isISOControl() }) return "$name must not contain control characters"
        return null
    }
}
