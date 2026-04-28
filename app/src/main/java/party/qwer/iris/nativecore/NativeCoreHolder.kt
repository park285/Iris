package party.qwer.iris.nativecore

internal object NativeCoreHolder {
    @Volatile
    private var runtime: NativeCoreRuntime = NativeCoreRuntime.create(env = emptyMap())

    fun current(): NativeCoreRuntime = runtime

    fun install(newRuntime: NativeCoreRuntime) {
        runtime = newRuntime
    }

    fun resetForTest() {
        runtime = NativeCoreRuntime.create(env = emptyMap())
    }
}
