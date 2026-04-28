package party.qwer.iris.nativecore

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NativeCoreDiagnosticsTest {
    @Test
    fun `off diagnostics has no readiness failure`() {
        val diagnostics =
            NativeCoreDiagnostics(
                mode = "off",
                loaded = false,
                libraryPath = "/data/iris/lib/libiris_native_core.so",
            )

        assertNull(diagnostics.readinessFailureReason())
    }

    @Test
    fun `on mode requires native core to be loaded`() {
        val diagnostics =
            NativeCoreDiagnostics(
                mode = "on",
                loaded = false,
                libraryPath = "/data/iris/lib/libiris_native_core.so",
                selfTestOk = true,
            )

        assertEquals("native core not loaded", diagnostics.readinessFailureReason())
    }

    @Test
    fun `on mode requires native core self test to pass`() {
        val diagnostics =
            NativeCoreDiagnostics(
                mode = "on",
                loaded = true,
                libraryPath = "/data/iris/lib/libiris_native_core.so",
                selfTestOk = false,
            )

        assertEquals("native core self-test failed", diagnostics.readinessFailureReason())
    }

    @Test
    fun `readiness mode check ignores case and whitespace`() {
        val diagnostics =
            NativeCoreDiagnostics(
                mode = " ON ",
                loaded = false,
                libraryPath = "/data/iris/lib/libiris_native_core.so",
                selfTestOk = true,
            )

        assertEquals("native core not loaded", diagnostics.readinessFailureReason())
    }

    @Test
    fun `non on modes do not block readiness`() {
        val diagnostics =
            NativeCoreDiagnostics(
                mode = "shadow",
                loaded = false,
                libraryPath = "/data/iris/lib/libiris_native_core.so",
                selfTestOk = false,
            )

        assertNull(diagnostics.readinessFailureReason())
    }

    @Test
    fun `component on mode blocks readiness when native core is unavailable`() {
        val diagnostics =
            NativeCoreDiagnostics(
                mode = "shadow",
                loaded = false,
                libraryPath = "/data/iris/lib/libiris_native_core.so",
                selfTestOk = false,
                componentStats =
                    mapOf(
                        "routing" to NativeCoreComponentDiagnostics(mode = "on"),
                        "decrypt" to NativeCoreComponentDiagnostics(mode = "shadow"),
                    ),
            )

        assertEquals("native core not loaded", diagnostics.readinessFailureReason())
    }

    @Test
    fun `component stats serialize additively with counters and last error`() {
        val diagnostics =
            NativeCoreDiagnostics(
                mode = "on",
                loaded = true,
                libraryPath = "/data/iris/lib/libiris_native_core.so",
                version = "iris-native-core:test",
                enabledComponents = listOf("decrypt"),
                selfTestOk = true,
                callFailures = 1,
                shadowMismatches = mapOf("decrypt" to 2),
                componentStats =
                    mapOf(
                        "decrypt" to
                            NativeCoreComponentDiagnostics(
                                mode = "on",
                                jniCalls = 3,
                                items = 7,
                                fallbacks = 4,
                                shadowMismatches = 2,
                                totalNativeMicros = 70,
                                averageNativeMicros = 23,
                                averageItemNativeMicros = 10,
                                fallbacksByKey = mapOf("roomTitle" to 1),
                                fallbackReasons = mapOf("itemError" to 2, "responseSizeMismatch" to 1),
                                failureReasons = mapOf("itemError" to 1),
                                shadowMismatchesByKey = mapOf("periodSpec" to 2),
                                parserDefaultUses = 3,
                                parserDefaultUsesByKey = mapOf("periodSpec" to 3),
                                lastError = "native decrypt failed",
                            ),
                    ),
            )

        val json = Json.encodeToString(diagnostics)
        val root = Json.parseToJsonElement(json).jsonObject
        val decryptStats =
            root
                .getValue("componentStats")
                .jsonObject
                .getValue("decrypt")
                .jsonObject

        assertEquals("on", decryptStats.getValue("mode").jsonPrimitive.content)
        assertEquals(3L, decryptStats.getValue("jniCalls").jsonPrimitive.long)
        assertEquals(7L, decryptStats.getValue("items").jsonPrimitive.long)
        assertEquals(4L, decryptStats.getValue("fallbacks").jsonPrimitive.long)
        assertEquals(2L, decryptStats.getValue("shadowMismatches").jsonPrimitive.long)
        assertEquals(10L, decryptStats.getValue("averageItemNativeMicros").jsonPrimitive.long)
        assertEquals(
            1L,
            decryptStats
                .getValue("fallbacksByKey")
                .jsonObject
                .getValue("roomTitle")
                .jsonPrimitive
                .long,
        )
        assertEquals(
            2L,
            decryptStats
                .getValue("fallbackReasons")
                .jsonObject
                .getValue("itemError")
                .jsonPrimitive
                .long,
        )
        assertEquals(
            1L,
            decryptStats
                .getValue("failureReasons")
                .jsonObject
                .getValue("itemError")
                .jsonPrimitive
                .long,
        )
        assertEquals(
            2L,
            decryptStats
                .getValue("shadowMismatchesByKey")
                .jsonObject
                .getValue("periodSpec")
                .jsonPrimitive
                .long,
        )
        assertEquals(3L, decryptStats.getValue("parserDefaultUses").jsonPrimitive.long)
        assertEquals(
            3L,
            decryptStats
                .getValue("parserDefaultUsesByKey")
                .jsonObject
                .getValue("periodSpec")
                .jsonPrimitive
                .long,
        )
        assertEquals("native decrypt failed", decryptStats.getValue("lastError").jsonPrimitive.content)
        assertEquals("on", root.getValue("mode").jsonPrimitive.content)
        assertEquals(1L, root.getValue("callFailures").jsonPrimitive.long)
    }

    @Test
    fun `diagnostics carries no secret fields`() {
        val propertyNames =
            (NativeCoreDiagnostics::class.java.declaredFields + NativeCoreComponentDiagnostics::class.java.declaredFields)
                .map { it.name.lowercase() }
                .toSet()

        assertFalse(propertyNames.any { name -> "token" in name })
        assertFalse(propertyNames.any { name -> "secret" in name })
        assertFalse(propertyNames.any { name -> "payload" in name })
    }
}
