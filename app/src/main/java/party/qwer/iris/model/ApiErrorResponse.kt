package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorResponse(
    val error: String,
    val code: String,
)
