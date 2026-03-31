package party.qwer.iris.imagebridge.runtime

import party.qwer.iris.ImageBridgeProtocol

internal class BridgeHandshakeValidator(
    private val expectedToken: String = party.qwer.iris.resolveBridgeToken(),
    private val securityMode: BridgeSecurityMode = BridgeSecurityMode.fromEnv(),
) {
    fun validate(request: ImageBridgeProtocol.ImageBridgeRequest) {
        require(request.protocolVersion == ImageBridgeProtocol.PROTOCOL_VERSION) {
            "unsupported protocol version"
        }
        when (securityMode) {
            BridgeSecurityMode.PRODUCTION -> {
                require(expectedToken.isNotBlank()) {
                    "bridge token must be configured in production mode"
                }
                require(request.token == expectedToken) { "unauthorized bridge token" }
            }
            BridgeSecurityMode.DEVELOPMENT -> {
                if (expectedToken.isNotBlank()) {
                    require(request.token == expectedToken) { "unauthorized bridge token" }
                }
            }
        }
    }
}
