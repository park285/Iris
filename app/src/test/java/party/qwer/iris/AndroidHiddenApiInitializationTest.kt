package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertNotNull

class AndroidHiddenApiInitializationTest {
    @Test
    fun `loading AndroidHiddenApi does not fail during class initialization`() {
        val loadedClass =
            runCatching {
                Class.forName("party.qwer.iris.AndroidHiddenApi")
            }.getOrElse { throwable ->
                throw AssertionError(
                    "AndroidHiddenApi class initialization should not eagerly resolve hidden API handles",
                    throwable,
                )
            }

        assertNotNull(loadedClass)
    }
}
