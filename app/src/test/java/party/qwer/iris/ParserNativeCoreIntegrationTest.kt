package party.qwer.iris

import party.qwer.iris.model.PeriodSpec
import party.qwer.iris.nativecore.NativeCoreHolder
import party.qwer.iris.nativecore.NativeCoreJniBridge
import party.qwer.iris.nativecore.NativeCoreRuntime
import kotlin.test.Test
import kotlin.test.assertEquals

class ParserNativeCoreIntegrationTest {
    @Test
    fun `room title parser shadow keeps kotlin result and records native fallback`() {
        val jni =
            FakeNativeCoreJni(
                parserResponse = """{"items":[{"kind":"roomTitle","ok":true,"fallback":true,"roomTitle":null}]}""",
            )
        val runtime = parserRuntime(mode = "shadow", jni = jni)

        withNativeRuntime(runtime) {
            val title = RoomMetaParser().parseRoomTitle("""[{"type":3,"content":"  Kotlin Room  "}]""")
            val parserStats = runtime.diagnostics().componentStats.getValue("parsers")

            assertEquals("Kotlin Room", title)
            assertEquals(1, jni.parserCalls)
            assertEquals(1L, parserStats.fallbacksByKey.getValue("roomTitle"))
        }
    }

    @Test
    fun `notice parser shadow keeps kotlin result and records mismatch by variant`() {
        val jni =
            FakeNativeCoreJni(
                parserResponse = """{"items":[{"kind":"notices","ok":true,"fallback":false,"notices":[]}]}""",
            )
        val runtime = parserRuntime(mode = "shadow", jni = jni)
        val meta =
            """
            {
              "noticeActivityContents": [
                {"message":"공지", "authorId":123, "createdAt":456}
              ]
            }
            """.trimIndent()

        withNativeRuntime(runtime) {
            val notices = RoomMetaParser().parseNotices(meta)
            val parserStats = runtime.diagnostics().componentStats.getValue("parsers")

            assertEquals(1, notices.size)
            assertEquals("공지", notices.single().content)
            assertEquals(1L, parserStats.shadowMismatchesByKey.getValue("notices"))
        }
    }

    @Test
    fun `id array parser on returns native ids`() {
        val jni =
            FakeNativeCoreJni(
                parserResponse = """{"items":[{"kind":"idArray","ok":true,"fallback":false,"ids":[5,6]}]}""",
            )
        val runtime = parserRuntime(mode = "on", jni = jni)

        withNativeRuntime(runtime) {
            val ids = JsonIdArrayParser().parse("[1,2]")

            assertEquals(setOf(5L, 6L), ids)
            assertEquals(1, jni.parserCalls)
        }
    }

    @Test
    fun `period parser on falls back when native parser requests fallback`() {
        val jni =
            FakeNativeCoreJni(
                parserResponse =
                    """{"items":[{"kind":"periodSpec","ok":true,"fallback":true,"periodSpec":{"kind":"days","days":99,"seconds":8553600}}]}""",
            )
        val runtime = parserRuntime(mode = "on", jni = jni)

        withNativeRuntime(runtime) {
            val period = PeriodSpecParser(defaultDays = 7).parse("bad")
            val parserStats = runtime.diagnostics().componentStats.getValue("parsers")

            assertEquals(PeriodSpec.Days(7), period)
            assertEquals(1L, parserStats.fallbacksByKey.getValue("periodSpec"))
        }
    }

    private fun parserRuntime(
        mode: String,
        jni: NativeCoreJniBridge,
    ): NativeCoreRuntime =
        NativeCoreRuntime.create(
            env =
                mapOf(
                    "IRIS_NATIVE_CORE" to "on",
                    "IRIS_NATIVE_DECRYPT" to "off",
                    "IRIS_NATIVE_PARSERS" to mode,
                ),
            loader = {},
            jni = jni,
        )

    private fun withNativeRuntime(
        runtime: NativeCoreRuntime,
        block: () -> Unit,
    ) {
        try {
            NativeCoreHolder.install(runtime)
            block()
        } finally {
            NativeCoreHolder.resetForTest()
        }
    }

    private class FakeNativeCoreJni(
        private val parserResponse: String,
    ) : NativeCoreJniBridge {
        var parserCalls = 0
            private set

        override fun nativeSelfTest(): String = "iris-native-core:test"

        override fun decryptBatch(requestJsonBytes: ByteArray): ByteArray = error("decrypt should not be called")

        override fun parserBatch(requestJsonBytes: ByteArray): ByteArray {
            parserCalls += 1
            return parserResponse.encodeToByteArray()
        }
    }
}
