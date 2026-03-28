package party.qwer.iris

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import party.qwer.iris.model.ConfigRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigRequestCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `config request rejects numeric strings`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<ConfigRequest>(
                """{"endpoint":"http://example","route":"default","rate":"50","port":"3000"}""",
            )
        }
    }

    @Test
    fun `config request still accepts numeric values`() {
        val request =
            json.decodeFromString<ConfigRequest>(
                """{"endpoint":"http://example","route":"default","rate":50,"port":3000}""",
            )

        assertEquals(50L, request.rate)
        assertEquals(3000, request.port)
    }
}
