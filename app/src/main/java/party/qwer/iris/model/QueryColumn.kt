package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class QueryColumn(
    val name: String,
    val sqliteType: String,
)
