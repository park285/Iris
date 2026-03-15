package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals

class IrisServerH2cTest {
    @Test
    fun `inbound server selects an h2c capable engine`() {
        assertEquals(IrisServerEngine.NETTY, irisServerEngine())
    }

    @Test
    fun `inbound netty server enables http2 and h2c`() {
        assertEquals(
            IrisServerTransportConfig(
                enableHttp2 = true,
                enableH2c = true,
            ),
            irisServerTransportConfig(),
        )
    }
}
