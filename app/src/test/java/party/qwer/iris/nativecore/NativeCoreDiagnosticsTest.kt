package party.qwer.iris.nativecore

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
    fun `diagnostics carries no secret fields`() {
        val propertyNames = NativeCoreDiagnostics::class.java.declaredFields.map { it.name }.toSet()

        assertFalse("token" in propertyNames)
        assertFalse("secret" in propertyNames)
        assertFalse("payload" in propertyNames)
    }
}
