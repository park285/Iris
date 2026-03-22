package party.qwer.iris

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KakaoDecryptTest {
    @Test
    fun `genSalt returns zero bytes for non positive user id`() {
        val salt = genSalt(userId = 0L, encType = 0)

        assertContentEquals(ByteArray(16), salt)
    }

    @Test
    fun `genSalt is deterministic for same inputs`() {
        val userId = 123456789L
        val encType = 15

        val first = genSalt(userId = userId, encType = encType)
        val second = genSalt(userId = userId, encType = encType)

        assertContentEquals(first, second)
    }

    @Test
    fun `genSalt changes when encType changes`() {
        val userId = 123456789L
        val salts =
            listOf(0, 2, 15, 16, 30, 31)
                .map { encType -> encType to genSalt(userId = userId, encType = encType).contentToString() }
                .toMap()

        assertEquals(salts.size, salts.values.toSet().size)
    }

    @Test
    fun `decrypt returns empty string for empty ciphertext`() {
        assertEquals("", KakaoDecrypt.decrypt(encType = 0, b64_ciphertext = "", user_id = 1L))
    }

    @Test
    fun `decrypt round trips short plaintext values`() {
        val cases = listOf("{}", "a")

        cases.forEach { plaintext ->
            val ciphertext = encrypt(encType = 0, plaintext = plaintext, userId = 987654321L)

            assertEquals(plaintext, KakaoDecrypt.decrypt(encType = 0, b64_ciphertext = ciphertext, user_id = 987654321L))
        }
    }

    @Test
    fun `decrypt throws for malformed base64 inputs`() {
        val malformed = "not_base64!!!"

        assertFailsWith<IllegalArgumentException> {
            KakaoDecrypt.decrypt(encType = 0, b64_ciphertext = malformed, user_id = 1L)
        }
    }

    @Test
    fun `decrypt is deterministic for same ciphertext and user`() {
        val ciphertext = encrypt(encType = 23, plaintext = "deterministic payload", userId = 24680L)

        val first = KakaoDecrypt.decrypt(encType = 23, b64_ciphertext = ciphertext, user_id = 24680L)
        val second = KakaoDecrypt.decrypt(encType = 23, b64_ciphertext = ciphertext, user_id = 24680L)

        assertEquals(first, second)
        assertEquals("deterministic payload", first)
    }

    @Test
    fun `decrypt output differs when user id changes for same ciphertext when alternate key yields decodable payload`() {
        val match =
            (0..5_000).firstNotNullOfOrNull { index ->
                val plaintext = "payload-$index"
                val ciphertext = encrypt(encType = 7, plaintext = plaintext, userId = 11111L)
                val correctUserOutput = KakaoDecrypt.decrypt(encType = 7, b64_ciphertext = ciphertext, user_id = 11111L)
                val wrongUserOutput =
                    runCatching {
                        KakaoDecrypt.decrypt(encType = 7, b64_ciphertext = ciphertext, user_id = 22222L)
                    }.getOrNull()

                if (wrongUserOutput != null && wrongUserOutput != correctUserOutput) {
                    Triple(plaintext, correctUserOutput, wrongUserOutput)
                } else {
                    null
                }
            }

        requireNotNull(match) { "No ciphertext produced a distinct decodable plaintext for the alternate user id" }
        val (plaintext, correctUserOutput, wrongUserOutput) = match

        assertEquals(plaintext, correctUserOutput)
        assertNotEquals(correctUserOutput, wrongUserOutput)
    }

    @Test
    fun `decrypt with wrong key does not return correct plaintext`() {
        val ciphertext = encrypt(encType = 0, plaintext = "secret message here!", userId = 111L)
        // 잘못된 키: padding validation 실패(IllegalArgumentException) 또는 garbage 반환
        val result =
            runCatching {
                KakaoDecrypt.decrypt(encType = 0, b64_ciphertext = ciphertext, user_id = 999L)
            }
        if (result.isSuccess) {
            assertNotEquals("secret message here!", result.getOrThrow())
        }
        // 실패 시 IllegalArgumentException (invalid padding) -> 올바른 키 없이 원문 복원 불가 확인
    }

    @Test
    fun `decrypt keyCache hit returns same result`() {
        val ciphertext = encrypt(encType = 5, plaintext = "cache test", userId = 42L)
        // 동일 salt 경로를 두 번 호출하여 캐시 적중 확인
        val first = KakaoDecrypt.decrypt(encType = 5, b64_ciphertext = ciphertext, user_id = 42L)
        val second = KakaoDecrypt.decrypt(encType = 5, b64_ciphertext = ciphertext, user_id = 42L)
        assertEquals("cache test", first)
        assertEquals(first, second)
    }

    @Test
    fun `decrypt is thread-safe under concurrent access`() {
        val threadCount = 8
        val iterationsPerThread = 50
        val plaintexts = (1..5).map { "concurrent-test-$it" }
        val ciphertexts =
            plaintexts.map { plaintext ->
                plaintext to encrypt(encType = 3, plaintext = plaintext, userId = 77777L)
            }

        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val latch = java.util.concurrent.CountDownLatch(threadCount)
        val threads =
            (1..threadCount).map { threadIdx ->
                Thread {
                    try {
                        repeat(iterationsPerThread) { i ->
                            val (expected, ct) = ciphertexts[(threadIdx + i) % ciphertexts.size]
                            val result = KakaoDecrypt.decrypt(encType = 3, b64_ciphertext = ct, user_id = 77777L)
                            if (result != expected) {
                                errors.add("thread=$threadIdx iter=$i: expected='$expected' got='$result'")
                            }
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
        threads.forEach { it.start() }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(emptyList(), errors.toList())
    }

    @Test
    fun `keyCache is bounded and does not exceed max size`() {
        val cache = readKeyCache()
        synchronized(cache) {
            cache.clear()
        }

        // 513개 유니크 키를 생성하여 캐시 상한(512)을 초과시킨다
        for (i in 0 until 513) {
            KakaoDecrypt.decrypt(0, encryptSample(i.toLong()), i.toLong())
        }

        synchronized(cache) {
            assertTrue(cache.size <= 512, "keyCache size ${cache.size} exceeds max 512")
        }
    }

    private fun genSalt(
        userId: Long,
        encType: Int,
    ): ByteArray {
        val method =
            KakaoDecrypt.Companion::class.java.getDeclaredMethod(
                "genSalt",
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        method.isAccessible = true
        return method.invoke(KakaoDecrypt.Companion, userId, encType) as ByteArray
    }

    private fun deriveKey(
        passwordBytes: ByteArray,
        saltBytes: ByteArray,
        iterations: Int,
        dkeySize: Int,
    ): ByteArray {
        val method =
            KakaoDecrypt.Companion::class.java.getDeclaredMethod(
                "deriveKey",
                ByteArray::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        method.isAccessible = true
        return method.invoke(KakaoDecrypt.Companion, passwordBytes, saltBytes, iterations, dkeySize) as ByteArray
    }

    private fun readKeyCache(): LinkedHashMap<*, *> =
        KakaoDecrypt::class.java.getDeclaredField("keyCache").let { field ->
            field.isAccessible = true
            field.get(null) as LinkedHashMap<*, *>
        }

    private fun encryptSample(userId: Long): String = encrypt(encType = 0, plaintext = "test", userId = userId)

    private fun encrypt(
        encType: Int,
        plaintext: String,
        userId: Long,
    ): String {
        val passwordBytes =
            byteArrayOf(
                0x16.toByte(),
                0x08.toByte(),
                0x09.toByte(),
                0x6f.toByte(),
                0x02.toByte(),
                0x17.toByte(),
                0x2b.toByte(),
                0x08.toByte(),
                0x21.toByte(),
                0x21.toByte(),
                0x0a.toByte(),
                0x10.toByte(),
                0x03.toByte(),
                0x03.toByte(),
                0x07.toByte(),
                0x06.toByte(),
            )
        val ivBytes =
            byteArrayOf(
                0x0f.toByte(),
                0x08.toByte(),
                0x01.toByte(),
                0x00.toByte(),
                0x19.toByte(),
                0x47.toByte(),
                0x25.toByte(),
                0xdc.toByte(),
                0x15.toByte(),
                0xf5.toByte(),
                0x17.toByte(),
                0xe0.toByte(),
                0xe1.toByte(),
                0x15.toByte(),
                0x0c.toByte(),
                0x35.toByte(),
            )

        val salt = genSalt(userId = userId, encType = encType)
        val key = deriveKey(passwordBytes = passwordBytes, saltBytes = salt, iterations = 2, dkeySize = 32)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivBytes))

        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val paddingLength = 16 - (plaintextBytes.size % 16)
        val padded = plaintextBytes + ByteArray(paddingLength) { paddingLength.toByte() }

        return Base64.getEncoder().encodeToString(cipher.doFinal(padded))
    }
}
