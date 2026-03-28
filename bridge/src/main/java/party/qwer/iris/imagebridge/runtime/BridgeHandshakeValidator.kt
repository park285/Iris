package party.qwer.iris.imagebridge.runtime

import party.qwer.iris.ImageBridgeProtocol

internal class BridgeHandshakeValidator(
    private val expectedToken: String = System.getenv("IRIS_BRIDGE_TOKEN").orEmpty(),
) {
    fun validate(request: ImageBridgeProtocol.ImageBridgeRequest) {
        require(request.protocolVersion == ImageBridgeProtocol.PROTOCOL_VERSION) { "unsupported protocol version" }
        if (expectedToken.isNotBlank()) {
            require(request.token == expectedToken) { "unauthorized bridge token" }
        }
    }
}
