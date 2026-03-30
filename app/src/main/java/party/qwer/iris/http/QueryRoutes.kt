package party.qwer.iris.http

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import party.qwer.iris.ApiRequestException
import party.qwer.iris.ChatLogRepository
import party.qwer.iris.ConfigProvider
import party.qwer.iris.IrisLogger
import party.qwer.iris.buildQueryResponse
import party.qwer.iris.internalServerFailure
import party.qwer.iris.invalidRequest
import party.qwer.iris.model.QueryRequest
import party.qwer.iris.requireQueryText
import party.qwer.iris.requireReadOnlyQuery

private const val MAX_QUERY_REQUEST_BODY_BYTES = 256 * 1024
private const val MAX_QUERY_ROWS = 500

internal fun Route.installQueryRoutes(
    authSupport: AuthSupport,
    serverJson: Json,
    chatLogRepo: ChatLogRepository,
    config: ConfigProvider,
) {
    post("/query") {
        readProtectedBody(call, MAX_QUERY_REQUEST_BODY_BYTES).use { rawBody ->
            if (!authSupport.requireBotToken(call, method = "POST", bodySha256Hex = rawBody.sha256Hex)) {
                return@post
            }
            val queryRequest = rawBody.decodeJson(serverJson, QueryRequest.serializer())
            requireQueryText(queryRequest.query)
            requireReadOnlyQuery(queryRequest.query)

            try {
                val queryResult =
                    chatLogRepo.executeQuery(
                        queryRequest.query,
                        (queryRequest.bind?.map { it.content } ?: listOf()).toTypedArray(),
                        MAX_QUERY_ROWS + 1,
                    )
                if (queryResult.rows.size > MAX_QUERY_ROWS) {
                    invalidRequest("query returned too many rows; limit is $MAX_QUERY_ROWS")
                }
                call.respond(
                    buildQueryResponse(
                        queryResult = queryResult,
                        decrypt = queryRequest.decrypt,
                        config = config,
                    ),
                )
            } catch (e: ApiRequestException) {
                throw e
            } catch (e: Exception) {
                IrisLogger.error("[QueryRoutes] Query execution failed", e)
                internalServerFailure("query execution failed")
            }
        }
    }
}
