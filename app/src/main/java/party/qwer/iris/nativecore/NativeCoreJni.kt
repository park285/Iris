package party.qwer.iris.nativecore

internal interface NativeCoreJniBridge {
    fun nativeSelfTest(): String

    fun decryptBatch(requestJsonBytes: ByteArray): ByteArray
}

internal object NativeCoreJni : NativeCoreJniBridge {
    external override fun nativeSelfTest(): String

    external override fun decryptBatch(requestJsonBytes: ByteArray): ByteArray
}
