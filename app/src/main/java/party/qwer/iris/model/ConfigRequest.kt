package party.qwer.iris.model

import kotlinx.serialization.Serializable
import party.qwer.iris.util.StrictIntSerializer
import party.qwer.iris.util.StrictLongSerializer

@Serializable
data class ConfigRequest(
    val endpoint: String? = null,
    val route: String? = null,
    @Serializable(with = StrictLongSerializer::class)
    val rate: Long? = null,
    @Serializable(with = StrictIntSerializer::class)
    val port: Int? = null,
)
