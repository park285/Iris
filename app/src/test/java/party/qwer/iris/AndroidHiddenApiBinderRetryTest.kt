package party.qwer.iris

import android.os.DeadObjectException
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidHiddenApiBinderRetryTest {
    @Test
    fun `isDeadBinderFailure detects nested DeadObjectException`() {
        val error = InvocationTargetException(DeadObjectException())

        assertTrue(isDeadBinderFailure(error))
    }

    @Test
    fun `isDeadBinderFailure returns false for unrelated failure`() {
        assertFalse(isDeadBinderFailure(IllegalStateException("no dead binder")))
    }

    @Test
    fun `retryOnDeadBinderFailure retries once and succeeds`() {
        var attempts = 0
        var retries = 0

        val result =
            retryOnDeadBinderFailure(
                onRetry = { retries += 1 },
            ) {
                attempts += 1
                if (attempts == 1) {
                    throw InvocationTargetException(DeadObjectException())
                }
                "ok"
            }

        assertEquals("ok", result)
        assertEquals(2, attempts)
        assertEquals(1, retries)
    }

    @Test
    fun `retryOnDeadBinderFailure does not retry unrelated failure`() {
        var attempts = 0
        var retries = 0

        val error =
            assertFailsWith<IllegalStateException> {
                retryOnDeadBinderFailure(
                    onRetry = { retries += 1 },
                ) {
                    attempts += 1
                    throw IllegalStateException("boom")
                }
            }

        assertEquals("boom", error.message)
        assertEquals(1, attempts)
        assertEquals(0, retries)
    }

    @Test
    fun `retryOnDeadBinderFailure stops after configured retries`() {
        var attempts = 0
        var retries = 0

        assertFailsWith<InvocationTargetException> {
            retryOnDeadBinderFailure(
                maxRetries = 1,
                onRetry = { retries += 1 },
            ) {
                attempts += 1
                throw InvocationTargetException(DeadObjectException())
            }
        }

        assertEquals(2, attempts)
        assertEquals(1, retries)
    }
}
