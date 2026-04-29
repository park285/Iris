package party.qwer.iris.config

data class RoutingPolicy(
    val commandRoutePrefixes: Map<String, List<String>>,
    val imageMessageTypeRoutes: Map<String, List<String>>,
    val eventTypeRoutes: Map<String, List<String>>,
    val requiresExternalBootstrap: Boolean,
)
