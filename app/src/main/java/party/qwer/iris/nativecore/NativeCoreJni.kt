package party.qwer.iris.nativecore

internal interface NativeCoreJniBridge {
    fun nativeSelfTest(): String

    fun decryptBatch(requestJsonBytes: ByteArray): ByteArray

    fun routingBatch(requestJsonBytes: ByteArray): ByteArray = error("native routing unsupported")

    fun parserBatch(requestJsonBytes: ByteArray): ByteArray = error("native parsers unsupported")

    fun webhookPayloadBatch(requestJsonBytes: ByteArray): ByteArray = error("native webhook payload unsupported")

    fun ingressBatch(requestJsonBytes: ByteArray): ByteArray = error("native ingress unsupported")
}

internal object NativeCoreJni : NativeCoreJniBridge {
    external override fun nativeSelfTest(): String

    external override fun decryptBatch(requestJsonBytes: ByteArray): ByteArray

    external override fun routingBatch(requestJsonBytes: ByteArray): ByteArray

    external override fun parserBatch(requestJsonBytes: ByteArray): ByteArray

    external override fun webhookPayloadBatch(requestJsonBytes: ByteArray): ByteArray

    external override fun ingressBatch(requestJsonBytes: ByteArray): ByteArray
}
