package party.qwer.iris

import io.ktor.http.Parameters
import kotlin.test.Test
import kotlin.test.assertEquals

class CanonicalRequestTargetTest {
    @Test
    fun `canonicalRequestTarget percent encodes and sorts query components`() {
        val target =
            canonicalRequestTarget(
                path = "/query",
                queryParameters =
                    Parameters.build {
                        append("symbols", "a&b=c%")
                        append("room name", "한글 채팅")
                    },
            )

        assertEquals(
            "/query?room%20name=%ED%95%9C%EA%B8%80%20%EC%B1%84%ED%8C%85&symbols=a%26b%3Dc%25",
            target,
        )
    }
}
