package party.qwer.iris

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import party.qwer.iris.model.QueryColumn
import kotlin.test.Test
import kotlin.test.assertEquals

class QueryResponseBuilderTest {
    private val noopConfig =
        object : ConfigProvider {
            override val botId = 0L
            override val botName = ""
            override val botSocketPort = 0
            override val botToken = ""
            override val webhookToken = ""
            override val dbPollingRate = 1000L
            override val messageSendRate = 0L
            override val messageSendJitterMax = 0L

            override fun webhookEndpointFor(route: String): String = ""
        }

    @Test
    fun `buildQueryResponse keeps raw rows when decrypt disabled`() {
        var decryptCalls = 0
        val queryResult =
            QueryExecutionResult(
                columns =
                    listOf(
                        QueryColumn(name = "user_id", sqliteType = "INTEGER"),
                        QueryColumn(name = "message", sqliteType = "TEXT"),
                    ),
                rows =
                    listOf(
                        listOf(
                            JsonPrimitive(7),
                            JsonPrimitive("ciphertext"),
                        ),
                    ),
            )

        val response =
            buildQueryResponse(
                queryResult = queryResult,
                decrypt = false,
                config = noopConfig,
                decryptor = { row, _ ->
                    decryptCalls += 1
                    row + ("message" to "decrypted")
                },
            )

        assertEquals(0, decryptCalls)
        assertEquals(
            "ciphertext",
            response.rows
                .single()[1]
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `buildQueryResponse applies decryptor when decrypt enabled`() {
        var decryptCalls = 0
        val queryResult =
            QueryExecutionResult(
                columns =
                    listOf(
                        QueryColumn(name = "user_id", sqliteType = "INTEGER"),
                        QueryColumn(name = "message", sqliteType = "TEXT"),
                    ),
                rows =
                    listOf(
                        listOf(
                            JsonPrimitive(7),
                            JsonPrimitive("ciphertext"),
                        ),
                    ),
            )

        val response =
            buildQueryResponse(
                queryResult = queryResult,
                decrypt = true,
                config = noopConfig,
                decryptor = { row, _ ->
                    decryptCalls += 1
                    row + ("message" to "decrypted")
                },
            )

        assertEquals(1, decryptCalls)
        assertEquals(
            "decrypted",
            response.rows
                .single()[1]
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "7",
            response.rows
                .single()[0]
                ?.jsonPrimitive
                ?.content,
        )
    }
}
