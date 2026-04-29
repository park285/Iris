package party.qwer.iris.nativecore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import party.qwer.iris.CommandKind
import party.qwer.iris.ParsedCommand
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.model.NoticeInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeCoreRuntimeTest {
    @Test
    fun `off mode skips loader and reports ready`() {
        var loadCalls = 0
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "off"),
                loader = { loadCalls += 1 },
                jni = FakeJni(),
            )

        assertEquals(0, loadCalls)
        assertEquals("off", runtime.diagnostics().mode)
        assertFalse(runtime.diagnostics().loaded)
        assertNull(runtime.diagnostics().readinessFailureReason())
    }

    @Test
    fun `shadow mode load failure records error but stays ready`() {
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "shadow"),
                loader = { error("missing library") },
                jni = FakeJni(),
            )

        val diagnostics = runtime.diagnostics()
        assertEquals("shadow", diagnostics.mode)
        assertFalse(diagnostics.loaded)
        assertEquals("missing library", diagnostics.lastError)
        assertNull(diagnostics.readinessFailureReason())
    }

    @Test
    fun `on mode load failure records readiness failure`() {
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "on"),
                loader = { error("missing library") },
                jni = FakeJni(),
            )

        val diagnostics = runtime.diagnostics()
        assertEquals("on", diagnostics.mode)
        assertFalse(diagnostics.loaded)
        assertEquals("native core not loaded", diagnostics.readinessFailureReason())
    }

    @Test
    fun `successful load runs self test and records version`() {
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "shadow"),
                loader = {},
                jni = FakeJni(selfTestResult = "iris-native-core:0.1.0"),
            )

        val diagnostics = runtime.diagnostics()
        assertTrue(diagnostics.loaded)
        assertTrue(diagnostics.selfTestOk)
        assertEquals("iris-native-core:0.1.0", diagnostics.version)
    }

    @Test
    fun `on mode self test error result records failed self test without version`() {
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "on"),
                loader = {},
                jni = FakeJni(selfTestResult = "error:panic in native core"),
            )

        val diagnostics = runtime.diagnostics()
        assertTrue(diagnostics.loaded)
        assertFalse(diagnostics.selfTestOk)
        assertNull(diagnostics.version)
        assertEquals("native core self-test failed", diagnostics.lastError)
        assertEquals("native core self-test failed", diagnostics.readinessFailureReason())
    }

    @Test
    fun `on mode self test error result exposes no enabled components and skips native decrypt`() {
        val jni = FakeJni(selfTestResult = "error:panic in native core", decryptResult = "rust-result")
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "on"),
                loader = {},
                jni = jni,
            )

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }
        val diagnostics = runtime.diagnostics()

        assertEquals(emptyList(), diagnostics.enabledComponents)
        assertEquals("kotlin-result", result)
        assertNull(jni.lastDecryptRequest)
        assertEquals(0L, diagnostics.callFailures)
        assertEquals(
            1L,
            diagnostics.componentStats
                .getValue("decrypt")
                .fallbackReasons
                .getValue("nativeUnavailable"),
        )
    }

    @Test
    fun `strict mode self test error throws instead of native unavailable fallback`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_STRICT" to "on",
                    ),
                loader = {},
                jni = FakeJni(selfTestResult = "error:panic in native core", decryptResult = "rust-result"),
            )
        var kotlinFallbackCalls = 0

        val failure =
            assertFailsWith<IllegalStateException> {
                runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) {
                    kotlinFallbackCalls += 1
                    "kotlin-result"
                }
            }
        val diagnostics = runtime.diagnostics()
        val decryptStats = diagnostics.componentStats.getValue("decrypt")

        assertEquals("native decrypt failed", failure.message)
        assertEquals(0, kotlinFallbackCalls)
        assertEquals(1L, diagnostics.callFailures)
        assertEquals(0L, decryptStats.fallbacks)
        assertEquals(1L, decryptStats.failureReasons.getValue("nativeUnavailable"))
        assertFalse("nativeUnavailable" in decryptStats.fallbackReasons)
    }

    @Test
    fun `shadow mode self test error result skips native shadow decrypt`() {
        val jni = FakeJni(selfTestResult = "error:panic in native core", decryptResult = "rust-result")
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "shadow"),
                loader = {},
                jni = jni,
            )

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }
        val diagnostics = runtime.diagnostics()

        assertEquals("kotlin-result", result)
        assertNull(jni.lastDecryptRequest)
        assertEquals(0L, diagnostics.callFailures)
        assertEquals(0L, diagnostics.shadowMismatches["decrypt"])
    }

    @Test
    fun `decrypt sends request json with encType ciphertext and userId`() {
        val jni = FakeJni()
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "on"),
                loader = {},
                jni = jni,
            )

        runtime.decryptOrFallback(encType = 7, ciphertext = "cipher-secret", userId = 123L) { "kotlin-result" }

        val request = Json.decodeFromString<JsonObject>(jni.lastDecryptRequest!!.decodeToString())
        assertEquals(setOf("items"), request.keys)
        val items = request.getValue("items").jsonArray
        assertEquals(1, items.size)
        val item = items.single().jsonObject
        assertEquals(setOf("encType", "ciphertext", "userId"), item.keys)
        assertEquals(7, item.getValue("encType").jsonPrimitive.int)
        assertEquals("cipher-secret", item.getValue("ciphertext").jsonPrimitive.content)
        assertEquals(123L, item.getValue("userId").jsonPrimitive.long)
    }

    @Test
    fun `diagnostics include all component modes and zeroed stats`() {
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "on"),
                loader = {},
                jni = FakeJni(),
            )

        val diagnostics = runtime.diagnostics()

        assertEquals(listOf("decrypt"), diagnostics.enabledComponents)
        assertEquals(setOf("decrypt", "routing", "parsers", "webhookPayload"), diagnostics.componentStats.keys)
        assertEquals("on", diagnostics.componentStats.getValue("decrypt").mode)
        assertEquals("off", diagnostics.componentStats.getValue("routing").mode)
        assertEquals("off", diagnostics.componentStats.getValue("parsers").mode)
        assertEquals("off", diagnostics.componentStats.getValue("webhookPayload").mode)
        assertEquals(0L, diagnostics.componentStats.getValue("decrypt").jniCalls)
        assertEquals(0L, diagnostics.componentStats.getValue("decrypt").items)
        assertEquals(0L, diagnostics.componentStats.getValue("decrypt").fallbacks)
    }

    @Test
    fun `decrypt component off skips native even when global mode is on`() {
        val jni = FakeJni(decryptResult = "rust-result")
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                    ),
                loader = {},
                jni = jni,
            )

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }
        val diagnostics = runtime.diagnostics()

        assertEquals("kotlin-result", result)
        assertNull(jni.lastDecryptRequest)
        assertEquals(emptyList(), diagnostics.enabledComponents)
        assertEquals("off", diagnostics.componentStats.getValue("decrypt").mode)
    }

    @Test
    fun `routing component on returns native parsed command and records stats`() {
        val jni =
            FakeJni(
                rawRoutingResponse =
                    """{"items":[{"ok":true,"kind":"WEBHOOK","normalizedText":"!native","webhookRoute":"native","targetRoute":"native"}]}""",
            )
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_ROUTING" to "on",
                    ),
                loader = {},
                jni = jni,
            )

        val result =
            runtime.parseCommandOrFallback("!kotlin") {
                ParsedCommand(CommandKind.NONE, "!kotlin")
            }
        val routingStats = runtime.diagnostics().componentStats.getValue("routing")

        assertEquals(ParsedCommand(CommandKind.WEBHOOK, "!native"), result)
        assertEquals(1, jni.routingCalls)
        assertEquals(1L, routingStats.jniCalls)
        assertEquals(1L, routingStats.items)
        assertEquals(0L, routingStats.fallbacks)
    }

    @Test
    fun `routing component shadow returns kotlin decision and records mismatch`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_ROUTING" to "shadow",
                    ),
                loader = {},
                jni =
                    FakeJni(
                        rawRoutingResponse = """{"items":[{"ok":true,"kind":"NONE","normalizedText":"","eventRoute":"native-route"}]}""",
                    ),
            )

        val result =
            runtime.resolveEventRouteOrFallback("nickname_change", mapOf("chatbotgo" to listOf("nickname_change"))) {
                "kotlin-route"
            }
        val diagnostics = runtime.diagnostics()
        val routingStats = diagnostics.componentStats.getValue("routing")

        assertEquals("kotlin-route", result)
        assertEquals(1L, diagnostics.shadowMismatches["routing"])
        assertEquals(1L, routingStats.shadowMismatches)
        assertEquals(1L, routingStats.jniCalls)
    }

    @Test
    fun `routing component shadow compares only requested command parse output`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_ROUTING" to "shadow",
                    ),
                loader = {},
                jni =
                    FakeJni(
                        rawRoutingResponse =
                            """{"items":[{"ok":true,"kind":"WEBHOOK","normalizedText":"!ping","webhookRoute":"default","targetRoute":"default"}]}""",
                    ),
            )

        val result =
            runtime.parseCommandOrFallback("!ping") {
                ParsedCommand(CommandKind.WEBHOOK, "!ping")
            }
        val diagnostics = runtime.diagnostics()
        val routingStats = diagnostics.componentStats.getValue("routing")

        assertEquals(ParsedCommand(CommandKind.WEBHOOK, "!ping"), result)
        assertEquals(0L, diagnostics.shadowMismatches.getValue("routing"))
        assertEquals(0L, routingStats.shadowMismatches)
        assertEquals(1L, routingStats.jniCalls)
    }

    @Test
    fun `routing component on records native error kind without leaking raw error`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_ROUTING" to "on",
                    ),
                loader = {},
                jni =
                    FakeJni(
                        rawRoutingResponse = """{"items":[{"ok":false,"errorKind":"panic","error":"ciphertext=secret"}]}""",
                    ),
            )

        val result =
            runtime.parseCommandOrFallback("!ping") {
                ParsedCommand(CommandKind.NONE, "!kotlin")
            }
        val diagnostics = runtime.diagnostics()
        val routingStats = diagnostics.componentStats.getValue("routing")

        assertEquals(ParsedCommand(CommandKind.NONE, "!kotlin"), result)
        assertEquals(1L, diagnostics.callFailures)
        assertEquals(1L, routingStats.failureReasons.getValue("panic"))
        assertEquals(1L, routingStats.fallbackReasons.getValue("panic"))
        assertFalse(diagnostics.lastError!!.contains("secret"))
    }

    @Test
    fun `strict routing mode throws on native error without kotlin fallback`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_ROUTING" to "on",
                        "IRIS_NATIVE_STRICT_ROUTING" to "on",
                    ),
                loader = {},
                jni =
                    FakeJni(
                        rawRoutingResponse = """{"items":[{"ok":false,"errorKind":"panic","error":"secret"}]}""",
                    ),
            )
        var kotlinFallbackCalls = 0

        val failure =
            assertFailsWith<IllegalStateException> {
                runtime.parseCommandOrFallback("!ping") {
                    kotlinFallbackCalls += 1
                    ParsedCommand(CommandKind.NONE, "!kotlin")
                }
            }
        val diagnostics = runtime.diagnostics()
        val routingStats = diagnostics.componentStats.getValue("routing")

        assertEquals("native routing failed", failure.message)
        assertEquals(0, kotlinFallbackCalls)
        assertEquals(1L, diagnostics.callFailures)
        assertEquals(0L, routingStats.fallbacks)
        assertEquals(1L, routingStats.failureReasons.getValue("panic"))
        assertFalse("panic" in routingStats.fallbackReasons)
        assertFalse(diagnostics.lastError!!.contains("secret"))
    }

    @Test
    fun `routing batch records single native fallback error kind instead of response size mismatch`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_ROUTING" to "on",
                    ),
                loader = {},
                jni =
                    FakeJni(
                        rawRoutingResponse = """{"items":[{"ok":false,"errorKind":"panic","error":"native panic"}]}""",
                    ),
            )

        val result =
            runtime.parseCommandBatchOrFallback(listOf("!a", "!b")) {
                listOf(
                    ParsedCommand(CommandKind.NONE, "!a"),
                    ParsedCommand(CommandKind.NONE, "!b"),
                )
            }
        val routingStats = runtime.diagnostics().componentStats.getValue("routing")

        assertEquals(listOf(ParsedCommand(CommandKind.NONE, "!a"), ParsedCommand(CommandKind.NONE, "!b")), result)
        assertEquals(1L, routingStats.failureReasons.getValue("panic"))
        assertEquals(2L, routingStats.fallbackReasons.getValue("panic"))
        assertFalse("responseSizeMismatch" in routingStats.failureReasons)
    }

    @Test
    fun `parser component on falls back when native requests fallback`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_PARSERS" to "on",
                    ),
                loader = {},
                jni =
                    FakeJni(
                        rawParserResponse = """{"items":[{"kind":"roomTitle","ok":true,"fallback":true,"roomTitle":"native-title"}]}""",
                    ),
            )

        val result = runtime.parseRoomTitleOrFallback("{broken") { "kotlin-title" }
        val parserStats = runtime.diagnostics().componentStats.getValue("parsers")

        assertEquals("kotlin-title", result)
        assertEquals(1L, parserStats.jniCalls)
        assertEquals(1L, parserStats.items)
        assertEquals(1L, parserStats.fallbacks)
        assertEquals(1L, parserStats.fallbackReasons.getValue("fallbackRequired"))
    }

    @Test
    fun `strict parser mode throws when native requests fallback`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_PARSERS" to "on",
                        "IRIS_NATIVE_STRICT" to "on",
                    ),
                loader = {},
                jni =
                    FakeJni(
                        rawParserResponse = """{"items":[{"kind":"roomTitle","ok":true,"fallback":true,"roomTitle":"native-title"}]}""",
                    ),
            )
        var kotlinFallbackCalls = 0

        val failure =
            assertFailsWith<IllegalStateException> {
                runtime.parseRoomTitleOrFallback("{broken") {
                    kotlinFallbackCalls += 1
                    "kotlin-title"
                }
            }
        val parserStats = runtime.diagnostics().componentStats.getValue("parsers")

        assertEquals("native parsers failed", failure.message)
        assertEquals(0, kotlinFallbackCalls)
        assertEquals(1L, runtime.diagnostics().callFailures)
        assertEquals(0L, parserStats.fallbacks)
        assertEquals(1L, parserStats.failureReasons.getValue("fallbackRequired"))
        assertFalse("fallbackRequired" in parserStats.fallbackReasons)
    }

    @Test
    fun `strict parser batch throws when response size mismatches`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_PARSERS" to "on",
                        "IRIS_NATIVE_STRICT_PARSERS" to "on",
                    ),
                loader = {},
                jni =
                    FakeJni(
                        rawParserResponse = """{"items":[{"kind":"idArray","ok":true,"fallback":false,"ids":[99]}]}""",
                    ),
            )
        var kotlinFallbackCalls = 0

        assertFailsWith<IllegalStateException> {
            runtime.parseIdArraysOrFallback(listOf("[1]", "[2]")) {
                kotlinFallbackCalls += 1
                listOf(setOf(1L), setOf(2L))
            }
        }
        val parserStats = runtime.diagnostics().componentStats.getValue("parsers")

        assertEquals(0, kotlinFallbackCalls)
        assertEquals(0L, parserStats.fallbacks)
        assertEquals(1L, parserStats.failureReasons.getValue("responseSizeMismatch"))
        assertFalse("responseSizeMismatch" in parserStats.fallbackReasons)
    }

    @Test
    fun `parser component shadow records native fallback by parser variant`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_PARSERS" to "shadow",
                    ),
                loader = {},
                jni =
                    FakeJni(
                        rawParserResponse = """{"items":[{"kind":"roomTitle","ok":true,"fallback":true,"roomTitle":null}]}""",
                    ),
            )

        val result = runtime.parseRoomTitleOrFallback("{broken") { null }
        val parserStats = runtime.diagnostics().componentStats.getValue("parsers")

        assertNull(result)
        assertEquals(1L, parserStats.fallbacks)
        assertEquals(1L, parserStats.fallbacksByKey.getValue("roomTitle"))
        assertEquals(1L, parserStats.fallbackReasons.getValue("fallbackRequired"))
        assertEquals(0L, parserStats.shadowMismatches)
    }

    @Test
    fun `parser component on counts used default without forcing kotlin fallback`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_PARSERS" to "on",
                    ),
                loader = {},
                jni =
                    FakeJni(
                        rawParserResponse = """{"items":[{"kind":"periodSpec","ok":true,"fallback":false,"usedDefault":true,"periodSpec":{"kind":"days","days":14}}]}""",
                    ),
            )

        val result = runtime.parsePeriodSpecOrFallback(period = null, defaultDays = 7) { error("kotlin fallback should not run") }
        val parserStats = runtime.diagnostics().componentStats.getValue("parsers")

        assertEquals(
            party.qwer.iris.model.PeriodSpec
                .Days(14),
            result,
        )
        assertEquals(0L, parserStats.fallbacks)
        assertEquals(1L, parserStats.parserDefaultUses)
        assertEquals(1L, parserStats.parserDefaultUsesByKey.getValue("periodSpec"))
    }

    @Test
    fun `parser component shadow records mismatch by parser variant`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_PARSERS" to "shadow",
                    ),
                loader = {},
                jni =
                    FakeJni(
                        rawParserResponse = """{"items":[{"kind":"roomTitle","ok":true,"fallback":false,"roomTitle":"native-title"}]}""",
                    ),
            )

        val result = runtime.parseRoomTitleOrFallback("""[{"type":3,"content":"kotlin-title"}]""") { "kotlin-title" }
        val parserStats = runtime.diagnostics().componentStats.getValue("parsers")

        assertEquals("kotlin-title", result)
        assertEquals(1L, parserStats.shadowMismatches)
        assertEquals(1L, parserStats.shadowMismatchesByKey.getValue("roomTitle"))
    }

    @Test
    fun `parser batch in on mode uses one jni call for many room titles`() {
        val jni =
            FakeJni(
                rawParserResponse =
                    """
                    {
                      "items": [
                        {"kind":"roomTitle","ok":true,"fallback":false,"roomTitle":"native-a"},
                        {"kind":"roomTitle","ok":true,"fallback":false,"roomTitle":null},
                        {"kind":"roomTitle","ok":true,"fallback":false,"roomTitle":"native-c"}
                      ]
                    }
                    """.trimIndent(),
            )
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_PARSERS" to "on",
                    ),
                loader = {},
                jni = jni,
            )

        val result =
            runtime.parseRoomTitlesOrFallback(listOf("meta-a", null, "meta-c")) {
                listOf("kotlin-a", null, "kotlin-c")
            }
        val parserStats = runtime.diagnostics().componentStats.getValue("parsers")
        val request = Json.decodeFromString<JsonObject>(jni.lastParserRequest!!.decodeToString())

        assertEquals(listOf("native-a", null, "native-c"), result)
        assertEquals(1, jni.parserCalls)
        assertEquals(3, request.getValue("items").jsonArray.size)
        assertEquals(1L, parserStats.jniCalls)
        assertEquals(3L, parserStats.items)
        assertTrue(parserStats.totalNativeMicros > 0L)
        assertTrue(parserStats.averageItemNativeMicros > 0L)
    }

    @Test
    fun `parser batch in on mode uses one jni call for many id arrays`() {
        val jni =
            FakeJni(
                rawParserResponse =
                    """
                    {
                      "items": [
                        {"kind":"idArray","ok":true,"fallback":false,"ids":[1,2]},
                        {"kind":"idArray","ok":true,"fallback":false,"ids":[]},
                        {"kind":"idArray","ok":true,"fallback":false,"ids":[3]}
                      ]
                    }
                    """.trimIndent(),
            )
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_PARSERS" to "on",
                    ),
                loader = {},
                jni = jni,
            )

        val result =
            runtime.parseIdArraysOrFallback(listOf("[9]", null, "[10]")) {
                listOf(setOf(9L), emptySet(), setOf(10L))
            }
        val parserStats = runtime.diagnostics().componentStats.getValue("parsers")
        val request = Json.decodeFromString<JsonObject>(jni.lastParserRequest!!.decodeToString())
        val items = request.getValue("items").jsonArray

        assertEquals(listOf(setOf(1L, 2L), emptySet(), setOf(3L)), result)
        assertEquals(1, jni.parserCalls)
        assertEquals(3, items.size)
        assertTrue(
            items.all { item ->
                item.jsonObject
                    .getValue("kind")
                    .jsonPrimitive
                    .content == "idArray"
            },
        )
        assertEquals(1L, parserStats.jniCalls)
        assertEquals(3L, parserStats.items)
    }

    @Test
    fun `parser batch in on mode uses one jni call for many notices`() {
        val jni =
            FakeJni(
                rawParserResponse =
                    """
                    {
                      "items": [
                        {
                          "kind":"notices",
                          "ok":true,
                          "fallback":false,
                          "notices":[{"content":"공지 A","authorId":1,"updatedAt":10}]
                        },
                        {
                          "kind":"notices",
                          "ok":true,
                          "fallback":false,
                          "notices":[{"content":"공지 B","authorId":2,"updatedAt":20}]
                        }
                      ]
                    }
                    """.trimIndent(),
            )
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_PARSERS" to "on",
                    ),
                loader = {},
                jni = jni,
            )

        val result =
            runtime.parseNoticesBatchOrFallback(listOf("meta-a", "meta-b")) {
                listOf(emptyList(), emptyList())
            }
        val parserStats = runtime.diagnostics().componentStats.getValue("parsers")
        val request = Json.decodeFromString<JsonObject>(jni.lastParserRequest!!.decodeToString())
        val items = request.getValue("items").jsonArray

        assertEquals(
            listOf(
                listOf(NoticeInfo(content = "공지 A", authorId = 1L, updatedAt = 10L)),
                listOf(NoticeInfo(content = "공지 B", authorId = 2L, updatedAt = 20L)),
            ),
            result,
        )
        assertEquals(1, jni.parserCalls)
        assertEquals(2, items.size)
        assertTrue(
            items.all { item ->
                item.jsonObject
                    .getValue("kind")
                    .jsonPrimitive
                    .content == "notices"
            },
        )
        assertEquals(1L, parserStats.jniCalls)
        assertEquals(2L, parserStats.items)
    }

    @Test
    fun `parser batch returns empty results without jni calls`() {
        val jni = FakeJni()
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_PARSERS" to "on",
                    ),
                loader = {},
                jni = jni,
            )

        val ids = runtime.parseIdArraysOrFallback(emptyList<String?>()) { error("kotlin fallback should not run") }
        val notices = runtime.parseNoticesBatchOrFallback(emptyList<String?>()) { error("kotlin fallback should not run") }
        val parserStats = runtime.diagnostics().componentStats.getValue("parsers")

        assertEquals(emptyList(), ids)
        assertEquals(emptyList(), notices)
        assertEquals(0, jni.parserCalls)
        assertEquals(0L, parserStats.jniCalls)
        assertEquals(0L, parserStats.items)
    }

    @Test
    fun `parser batch in on mode falls back to kotlin when response size mismatches`() {
        val jni =
            FakeJni(
                rawParserResponse = """{"items":[{"kind":"idArray","ok":true,"fallback":false,"ids":[99]}]}""",
            )
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_PARSERS" to "on",
                    ),
                loader = {},
                jni = jni,
            )

        val result =
            runtime.parseIdArraysOrFallback(listOf("[1]", "[2]")) {
                listOf(setOf(1L), setOf(2L))
            }
        val parserStats = runtime.diagnostics().componentStats.getValue("parsers")

        assertEquals(listOf(setOf(1L), setOf(2L)), result)
        assertEquals(1, jni.parserCalls)
        assertEquals(1L, parserStats.jniCalls)
        assertEquals(2L, parserStats.items)
        assertEquals(2L, parserStats.fallbackReasons.getValue("responseSizeMismatch"))
        assertEquals(1L, parserStats.failureReasons.getValue("responseSizeMismatch"))
    }

    @Test
    fun `parser batch in on mode records whole batch fallback when one item requires fallback`() {
        val jni =
            FakeJni(
                rawParserResponse =
                    """
                    {
                      "items": [
                        {"kind":"idArray","ok":true,"fallback":true,"ids":[99]},
                        {"kind":"idArray","ok":true,"fallback":false,"ids":[2]}
                      ]
                    }
                    """.trimIndent(),
            )
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_PARSERS" to "on",
                    ),
                loader = {},
                jni = jni,
            )

        val result =
            runtime.parseIdArraysOrFallback(listOf("[1]", "[2]")) {
                listOf(setOf(1L), setOf(2L))
            }
        val parserStats = runtime.diagnostics().componentStats.getValue("parsers")

        assertEquals(listOf(setOf(1L), setOf(2L)), result)
        assertEquals(1, jni.parserCalls)
        assertEquals(2L, parserStats.fallbacks)
        assertEquals(2L, parserStats.fallbacksByKey.getValue("idArray"))
        assertEquals(2L, parserStats.fallbackReasons.getValue("fallbackRequired"))
    }

    @Test
    fun `parser mixed room info metadata uses one jni call for notices and blinded ids`() {
        val jni =
            FakeJni(
                rawParserResponse =
                    """
                    {
                      "items": [
                        {
                          "kind":"notices",
                          "ok":true,
                          "fallback":false,
                          "notices":[{"content":"점검","authorId":7,"updatedAt":100}]
                        },
                        {"kind":"idArray","ok":true,"fallback":false,"ids":[8,9]}
                      ]
                    }
                    """.trimIndent(),
            )
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_PARSERS" to "on",
                    ),
                loader = {},
                jni = jni,
            )

        val result =
            runtime.parseRoomInfoMetadataOrFallback(meta = "meta", blindedMemberIds = "[1]") {
                emptyList<NoticeInfo>() to emptySet()
            }
        val parserStats = runtime.diagnostics().componentStats.getValue("parsers")
        val request = Json.decodeFromString<JsonObject>(jni.lastParserRequest!!.decodeToString())
        val kinds =
            request
                .getValue("items")
                .jsonArray
                .map { item ->
                    item.jsonObject
                        .getValue("kind")
                        .jsonPrimitive
                        .content
                }

        assertEquals(listOf(NoticeInfo(content = "점검", authorId = 7L, updatedAt = 100L)) to setOf(8L, 9L), result)
        assertEquals(1, jni.parserCalls)
        assertEquals(listOf("notices", "idArray"), kinds)
        assertEquals(1L, parserStats.jniCalls)
        assertEquals(2L, parserStats.items)
    }

    @Test
    fun `webhook payload component on returns native payload`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_WEBHOOK_PAYLOAD" to "on",
                    ),
                loader = {},
                jni =
                    FakeJni(
                        rawWebhookPayloadResponse =
                            """{"items":[{"ok":true,"payloadJson":"{\"route\":\"native\",\"messageId\":\"msg-1\"}"}]}""",
                    ),
            )

        val result =
            runtime.buildWebhookPayloadOrFallback(
                command = sampleRoutingCommand(),
                route = "default",
                messageId = "msg-1",
            ) {
                """{"route":"kotlin","messageId":"msg-1"}"""
            }
        val webhookStats = runtime.diagnostics().componentStats.getValue("webhookPayload")

        assertEquals("""{"route":"native","messageId":"msg-1"}""", result)
        assertEquals(1L, webhookStats.jniCalls)
        assertEquals(1L, webhookStats.items)
        assertEquals(0L, webhookStats.fallbacks)
    }

    @Test
    fun `strict webhook payload mode throws on native error without kotlin fallback`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_WEBHOOK_PAYLOAD" to "on",
                        "IRIS_NATIVE_STRICT_WEBHOOK_PAYLOAD" to "on",
                    ),
                loader = {},
                jni =
                    FakeJni(
                        rawWebhookPayloadResponse = """{"items":[{"ok":false,"errorKind":"payloadPanic","error":"secret"}]}""",
                    ),
            )
        var kotlinFallbackCalls = 0

        val failure =
            assertFailsWith<IllegalStateException> {
                runtime.buildWebhookPayloadOrFallback(
                    command = sampleRoutingCommand(),
                    route = "default",
                    messageId = "msg-1",
                ) {
                    kotlinFallbackCalls += 1
                    """{"route":"kotlin","messageId":"msg-1"}"""
                }
            }
        val diagnostics = runtime.diagnostics()
        val webhookStats = diagnostics.componentStats.getValue("webhookPayload")

        assertEquals("native webhook payload failed", failure.message)
        assertEquals(0, kotlinFallbackCalls)
        assertEquals(1L, diagnostics.callFailures)
        assertEquals(0L, webhookStats.fallbacks)
        assertEquals(1L, webhookStats.failureReasons.getValue("itemError"))
        assertFalse("itemError" in webhookStats.fallbackReasons)
        assertFalse(diagnostics.lastError!!.contains("secret"))
    }

    @Test
    fun `webhook payload component shadow compares json semantically`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_WEBHOOK_PAYLOAD" to "shadow",
                    ),
                loader = {},
                jni =
                    FakeJni(
                        rawWebhookPayloadResponse =
                            """{"items":[{"ok":true,"payloadJson":"{\"messageId\":\"msg-1\",\"route\":\"default\"}"}]}""",
                    ),
            )

        val result =
            runtime.buildWebhookPayloadOrFallback(
                command = sampleRoutingCommand(),
                route = "default",
                messageId = "msg-1",
            ) {
                """{"route":"default","messageId":"msg-1"}"""
            }
        val diagnostics = runtime.diagnostics()
        val webhookStats = diagnostics.componentStats.getValue("webhookPayload")

        assertEquals("""{"route":"default","messageId":"msg-1"}""", result)
        assertEquals(0L, diagnostics.shadowMismatches.getValue("webhookPayload"))
        assertEquals(0L, webhookStats.shadowMismatches)
        assertEquals(1L, webhookStats.jniCalls)
    }

    @Test
    fun `ingress batch component on plans route and payload with one jni call for many commands`() {
        val jni =
            FakeJni(
                rawIngressResponse =
                    """
                    {
                      "items": [
                        {
                          "ok": true,
                          "kind": "WEBHOOK",
                          "normalizedText": "!native-a",
                          "targetRoute": "default",
                          "messageId": "kakao-log-101-default",
                          "payloadJson": "{\"route\":\"default\",\"messageId\":\"kakao-log-101-default\"}"
                        },
                        {
                          "ok": true,
                          "kind": "NONE",
                          "normalizedText": "photo",
                          "imageRoute": "chatbotgo",
                          "targetRoute": "chatbotgo",
                          "messageId": "kakao-log-102-chatbotgo",
                          "payloadJson": "{\"route\":\"chatbotgo\",\"messageId\":\"kakao-log-102-chatbotgo\"}"
                        }
                      ]
                    }
                    """.trimIndent(),
            )
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_ROUTING" to "on",
                        "IRIS_NATIVE_WEBHOOK_PAYLOAD" to "on",
                    ),
                loader = {},
                jni = jni,
            )

        val result =
            runtime.planIngressBatchOrFallback(
                commands =
                    listOf(
                        sampleRoutingCommand().copy(text = "!kotlin-a", sourceLogId = 101L),
                        sampleRoutingCommand().copy(text = "photo", sourceLogId = 102L, messageType = "2"),
                    ),
                commandRoutePrefixes = mapOf("default" to listOf("!")),
                imageMessageTypeRoutes = mapOf("chatbotgo" to listOf("2")),
                eventTypeRoutes = emptyMap(),
            ) {
                error("kotlin fallback should not run")
            }
        val diagnostics = runtime.diagnostics()
        val routingStats = diagnostics.componentStats.getValue("routing")
        val payloadStats = diagnostics.componentStats.getValue("webhookPayload")
        val request = Json.decodeFromString<JsonObject>(jni.lastIngressRequest!!.decodeToString())

        assertEquals(1, jni.ingressCalls)
        assertEquals(2, request.getValue("items").jsonArray.size)
        assertEquals(ParsedCommand(CommandKind.WEBHOOK, "!native-a"), result[0].parsedCommand)
        assertEquals("default", result[0].targetRoute)
        assertEquals("kakao-log-101-default", result[0].messageId)
        assertEquals("chatbotgo", result[1].targetRoute)
        assertEquals(1L, routingStats.jniCalls)
        assertEquals(2L, routingStats.items)
        assertEquals(1L, payloadStats.jniCalls)
        assertEquals(2L, payloadStats.items)
        assertTrue(routingStats.totalNativeMicros > 0L)
        assertTrue(payloadStats.totalNativeMicros > 0L)
    }

    @Test
    fun `strict ingress batch throws without kotlin planner fallback`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_ROUTING" to "on",
                        "IRIS_NATIVE_WEBHOOK_PAYLOAD" to "on",
                        "IRIS_NATIVE_STRICT" to "on",
                    ),
                loader = {},
                jni = FakeJni(rawIngressResponse = """{"items":[]}"""),
            )
        var kotlinFallbackCalls = 0

        val failure =
            assertFailsWith<IllegalStateException> {
                runtime.planIngressBatchOrFallback(
                    commands = listOf(sampleRoutingCommand()),
                    commandRoutePrefixes = mapOf("default" to listOf("!")),
                    imageMessageTypeRoutes = emptyMap(),
                    eventTypeRoutes = emptyMap(),
                ) {
                    kotlinFallbackCalls += 1
                    emptyList()
                }
            }
        val diagnostics = runtime.diagnostics()
        val routingStats = diagnostics.componentStats.getValue("routing")
        val payloadStats = diagnostics.componentStats.getValue("webhookPayload")

        assertEquals("native ingress failed", failure.message)
        assertEquals(0, kotlinFallbackCalls)
        assertEquals(1L, diagnostics.callFailures)
        assertEquals(0L, routingStats.fallbacks)
        assertEquals(0L, payloadStats.fallbacks)
        assertEquals(1L, routingStats.failureReasons.getValue("responseSizeMismatch"))
        assertEquals(1L, payloadStats.failureReasons.getValue("responseSizeMismatch"))
    }

    @Test
    fun `ingress batch component on falls back on native route payload build failure without leaking error text`() {
        val nativeErrorText = "secret-route-message-payload failure should stay private"
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_ROUTING" to "on",
                        "IRIS_NATIVE_WEBHOOK_PAYLOAD" to "on",
                    ),
                loader = {},
                jni =
                    FakeJni(
                        rawIngressResponse =
                            """
                            {
                              "items": [
                                {
                                  "ok": true,
                                  "kind": "WEBHOOK",
                                  "normalizedText": "!native",
                                  "targetRoute": "default",
                                  "error": "$nativeErrorText"
                                }
                              ]
                            }
                            """.trimIndent(),
                    ),
            )
        var kotlinFallbackCalls = 0

        val result =
            runtime.planIngressBatchOrFallback(
                commands = listOf(sampleRoutingCommand()),
                commandRoutePrefixes = mapOf("default" to listOf("!")),
                imageMessageTypeRoutes = emptyMap(),
                eventTypeRoutes = emptyMap(),
            ) {
                kotlinFallbackCalls += 1
                listOf(
                    NativeIngressPlan(
                        parsedCommand = ParsedCommand(CommandKind.WEBHOOK, "!kotlin"),
                        targetRoute = "kotlin-route",
                        messageId = "msg-kotlin",
                        payloadJson = """{"route":"kotlin-route","messageId":"msg-kotlin"}""",
                    ),
                )
            }
        val diagnostics = runtime.diagnostics()
        val routingStats = diagnostics.componentStats.getValue("routing")
        val payloadStats = diagnostics.componentStats.getValue("webhookPayload")

        assertEquals(1, kotlinFallbackCalls)
        assertEquals("kotlin-route", result.single().targetRoute)
        assertEquals("msg-kotlin", result.single().messageId)
        assertEquals("""{"route":"kotlin-route","messageId":"msg-kotlin"}""", result.single().payloadJson)
        assertEquals(1L, diagnostics.callFailures)
        assertEquals("native ingress failed", diagnostics.lastError)
        assertEquals(1L, routingStats.jniCalls)
        assertEquals(1L, payloadStats.jniCalls)
        assertEquals(1L, routingStats.fallbacks)
        assertEquals(1L, payloadStats.fallbacks)
        assertEquals(mapOf("schemaDecodeError" to 1L), routingStats.failureReasons)
        assertEquals(mapOf("schemaDecodeError" to 1L), payloadStats.failureReasons)
        assertEquals(mapOf("schemaDecodeError" to 1L), routingStats.fallbackReasons)
        assertEquals(mapOf("schemaDecodeError" to 1L), payloadStats.fallbackReasons)
        assertEquals("native ingress failed", routingStats.lastError)
        assertEquals("native ingress failed", payloadStats.lastError)
        assertFalse(diagnostics.lastError!!.contains(nativeErrorText))
        assertFalse(routingStats.lastError!!.contains(nativeErrorText))
        assertFalse(payloadStats.lastError!!.contains(nativeErrorText))
    }

    @Test
    fun `ingress batch component on falls back on native item error without leaking error text`() {
        val nativeErrorText = "secret-invalid-request detail should stay private"
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_ROUTING" to "on",
                        "IRIS_NATIVE_WEBHOOK_PAYLOAD" to "on",
                    ),
                loader = {},
                jni =
                    FakeJni(
                        rawIngressResponse =
                            """
                            {
                              "items": [
                                {
                                  "ok": false,
                                  "errorKind": "invalidRequest",
                                  "error": "$nativeErrorText"
                                }
                              ]
                            }
                            """.trimIndent(),
                    ),
            )
        var kotlinFallbackCalls = 0

        val result =
            runtime.planIngressBatchOrFallback(
                commands = listOf(sampleRoutingCommand()),
                commandRoutePrefixes = mapOf("default" to listOf("!")),
                imageMessageTypeRoutes = emptyMap(),
                eventTypeRoutes = emptyMap(),
            ) {
                kotlinFallbackCalls += 1
                listOf(
                    NativeIngressPlan(
                        parsedCommand = ParsedCommand(CommandKind.WEBHOOK, "!kotlin"),
                        targetRoute = "kotlin-route",
                        messageId = "msg-kotlin",
                        payloadJson = """{"route":"kotlin-route","messageId":"msg-kotlin"}""",
                    ),
                )
            }
        val diagnostics = runtime.diagnostics()
        val routingStats = diagnostics.componentStats.getValue("routing")
        val payloadStats = diagnostics.componentStats.getValue("webhookPayload")

        assertEquals(1, kotlinFallbackCalls)
        assertEquals("kotlin-route", result.single().targetRoute)
        assertEquals(1L, diagnostics.callFailures)
        assertEquals("native ingress failed", diagnostics.lastError)
        assertEquals(mapOf("invalidRequest" to 1L), routingStats.failureReasons)
        assertEquals(mapOf("invalidRequest" to 1L), payloadStats.failureReasons)
        assertEquals(mapOf("invalidRequest" to 1L), routingStats.fallbackReasons)
        assertEquals(mapOf("invalidRequest" to 1L), payloadStats.fallbackReasons)
        assertFalse(diagnostics.lastError!!.contains(nativeErrorText))
        assertFalse(routingStats.lastError!!.contains(nativeErrorText))
        assertFalse(payloadStats.lastError!!.contains(nativeErrorText))
    }

    @Test
    fun `routing on with webhook payload off uses routing batch and kotlin payload without ingress`() {
        val jni =
            FakeJni(
                rawRoutingResponse =
                    """{"items":[{"ok":true,"kind":"WEBHOOK","normalizedText":"!native","webhookRoute":"native","targetRoute":"native"}]}""",
            )
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_ROUTING" to "on",
                        "IRIS_NATIVE_WEBHOOK_PAYLOAD" to "off",
                    ),
                loader = {},
                jni = jni,
            )

        val result =
            runtime.planIngressBatchOrFallback(
                commands = listOf(sampleRoutingCommand()),
                commandRoutePrefixes = mapOf("native" to listOf("!")),
                imageMessageTypeRoutes = emptyMap(),
                eventTypeRoutes = emptyMap(),
            ) {
                error("full kotlin planner should not run when native routing succeeds")
            }

        assertEquals("native", result.single().targetRoute)
        assertEquals("kakao-log-100-native", result.single().messageId)
        assertTrue(result.single().payloadJson!!.contains("!native"))
        assertEquals(1, jni.routingCalls)
        assertEquals(0, jni.webhookPayloadCalls)
        assertEquals(0, jni.ingressCalls)
    }

    @Test
    fun `routing shadow with webhook payload off still compares routing batch`() {
        val jni =
            FakeJni(
                rawRoutingResponse =
                    """{"items":[{"ok":true,"kind":"WEBHOOK","normalizedText":"!native","webhookRoute":"native","targetRoute":"native"}]}""",
            )
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_ROUTING" to "shadow",
                        "IRIS_NATIVE_WEBHOOK_PAYLOAD" to "off",
                    ),
                loader = {},
                jni = jni,
            )

        val result =
            runtime.planIngressBatchOrFallback(
                commands = listOf(sampleRoutingCommand()),
                commandRoutePrefixes = mapOf("native" to listOf("!")),
                imageMessageTypeRoutes = emptyMap(),
                eventTypeRoutes = emptyMap(),
            ) {
                listOf(
                    NativeIngressPlan(
                        parsedCommand = ParsedCommand(CommandKind.WEBHOOK, "!kotlin"),
                        targetRoute = "default",
                        messageId = "msg-kotlin",
                        payloadJson = """{"route":"default","messageId":"msg-kotlin"}""",
                    ),
                )
            }

        assertEquals("default", result.single().targetRoute)
        assertEquals(1, jni.routingCalls)
        assertEquals(0, jni.webhookPayloadCalls)
        assertEquals(0, jni.ingressCalls)
        assertEquals(1L, runtime.diagnostics().shadowMismatches.getValue("routing"))
    }

    @Test
    fun `webhook payload shadow with routing off still compares payload batch`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_DECRYPT" to "off",
                        "IRIS_NATIVE_ROUTING" to "off",
                        "IRIS_NATIVE_WEBHOOK_PAYLOAD" to "shadow",
                    ),
                loader = {},
                jni = FakeJni(),
            )

        val result =
            runtime.planIngressBatchOrFallback(
                commands = listOf(sampleRoutingCommand()),
                commandRoutePrefixes = emptyMap(),
                imageMessageTypeRoutes = emptyMap(),
                eventTypeRoutes = emptyMap(),
            ) {
                listOf(
                    NativeIngressPlan(
                        parsedCommand = ParsedCommand(CommandKind.WEBHOOK, "!ping"),
                        targetRoute = "default",
                        messageId = "msg-kotlin",
                        payloadJson = """{"route":"default","messageId":"msg-kotlin"}""",
                    ),
                )
            }

        val diagnostics = runtime.diagnostics()

        assertEquals("""{"route":"default","messageId":"msg-kotlin"}""", result.single().payloadJson)
        assertEquals(0, diagnostics.componentStats.getValue("routing").jniCalls)
        assertEquals(1L, diagnostics.componentStats.getValue("webhookPayload").jniCalls)
        assertEquals(1L, diagnostics.shadowMismatches.getValue("webhookPayload"))
    }

    @Test
    fun `decrypt batch in on mode uses one jni call for many items and records item stats`() {
        val jni = FakeJni(decryptResults = listOf("rust-a", "rust-b", "rust-c"))
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "on"),
                loader = {},
                jni = jni,
            )

        val result =
            runtime.decryptBatchOrFallback(
                items =
                    listOf(
                        NativeDecryptBatchItem(encType = 1, ciphertext = "cipher-a", userId = 10L),
                        NativeDecryptBatchItem(encType = 2, ciphertext = "cipher-b", userId = 20L),
                        NativeDecryptBatchItem(encType = 3, ciphertext = "cipher-c", userId = 30L),
                    ),
                kotlinDecryptBatch = { listOf("kotlin-a", "kotlin-b", "kotlin-c") },
            )
        val diagnostics = runtime.diagnostics()
        val decryptStats = diagnostics.componentStats.getValue("decrypt")
        val request = Json.decodeFromString<JsonObject>(jni.lastDecryptRequest!!.decodeToString())

        assertEquals(listOf("rust-a", "rust-b", "rust-c"), result)
        assertEquals(1, jni.decryptCalls)
        assertEquals(3, request.getValue("items").jsonArray.size)
        assertEquals(1L, decryptStats.jniCalls)
        assertEquals(3L, decryptStats.items)
        assertEquals(0L, decryptStats.fallbacks)
        assertTrue(decryptStats.totalNativeMicros > 0L)
        assertTrue(decryptStats.maxNativeMicros > 0L)
        assertTrue(decryptStats.averageNativeMicros > 0L)
        assertTrue(decryptStats.averageItemNativeMicros > 0L)
        assertEquals(0L, diagnostics.callFailures)
    }

    @Test
    fun `decrypt batch in shadow mode returns kotlin results and counts each mismatch`() {
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "shadow"),
                loader = {},
                jni = FakeJni(decryptResults = listOf("rust-a", "same", "rust-c")),
            )

        val result =
            runtime.decryptBatchOrFallback(
                items =
                    listOf(
                        NativeDecryptBatchItem(encType = 1, ciphertext = "cipher-a", userId = 10L),
                        NativeDecryptBatchItem(encType = 2, ciphertext = "cipher-b", userId = 20L),
                        NativeDecryptBatchItem(encType = 3, ciphertext = "cipher-c", userId = 30L),
                    ),
                kotlinDecryptBatch = { listOf("kotlin-a", "same", "kotlin-c") },
            )
        val diagnostics = runtime.diagnostics()
        val decryptStats = diagnostics.componentStats.getValue("decrypt")

        assertEquals(listOf("kotlin-a", "same", "kotlin-c"), result)
        assertEquals(2L, diagnostics.shadowMismatches["decrypt"])
        assertEquals(2L, decryptStats.shadowMismatches)
        assertEquals(1L, decryptStats.jniCalls)
        assertEquals(3L, decryptStats.items)
    }

    @Test
    fun `decrypt in shadow mode returns kotlin result and counts mismatch`() {
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "shadow"),
                loader = {},
                jni = FakeJni(decryptResult = "rust-result"),
            )

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }

        assertEquals("kotlin-result", result)
        assertEquals(1L, runtime.diagnostics().shadowMismatches["decrypt"])
    }

    @Test
    fun `decrypt in on mode returns native result when loaded`() {
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "on"),
                loader = {},
                jni = FakeJni(decryptResult = "rust-result"),
            )

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }

        assertEquals("rust-result", result)
    }

    @Test
    fun `decrypt in on mode falls back when native call fails`() {
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "on"),
                loader = {},
                jni = FakeJni(decryptError = IllegalStateException("native failed")),
            )

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }

        assertEquals("kotlin-result", result)
        assertEquals(1L, runtime.diagnostics().callFailures)
        assertEquals("native decrypt failed", runtime.diagnostics().lastError)
        assertEquals(
            1L,
            runtime
                .diagnostics()
                .componentStats
                .getValue("decrypt")
                .failureReasons
                .getValue("jniError"),
        )
        assertEquals(
            1L,
            runtime
                .diagnostics()
                .componentStats
                .getValue("decrypt")
                .fallbackReasons
                .getValue("jniError"),
        )
    }

    @Test
    fun `strict decrypt mode throws when native call fails`() {
        val runtime =
            NativeCoreRuntime.create(
                env =
                    mapOf(
                        "IRIS_NATIVE_CORE" to "on",
                        "IRIS_NATIVE_STRICT" to "on",
                    ),
                loader = {},
                jni = FakeJni(decryptError = IllegalStateException("native failed")),
            )
        var kotlinFallbackCalls = 0

        val failure =
            assertFailsWith<IllegalStateException> {
                runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) {
                    kotlinFallbackCalls += 1
                    "kotlin-result"
                }
            }
        val diagnostics = runtime.diagnostics()
        val decryptStats = diagnostics.componentStats.getValue("decrypt")

        assertEquals("native decrypt failed", failure.message)
        assertEquals(0, kotlinFallbackCalls)
        assertEquals(1L, diagnostics.callFailures)
        assertEquals(0L, decryptStats.fallbacks)
        assertEquals(1L, decryptStats.failureReasons.getValue("jniError"))
        assertFalse("jniError" in decryptStats.fallbackReasons)
    }

    @Test
    fun `decrypt in on mode falls back when native response is malformed`() {
        val runtime = runtimeWithNativeResponse("not-json")

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }

        assertEquals("kotlin-result", result)
        assertEquals(1L, runtime.diagnostics().callFailures)
        assertEquals("native decrypt failed", runtime.diagnostics().lastError)
        assertEquals(
            1L,
            runtime
                .diagnostics()
                .componentStats
                .getValue("decrypt")
                .failureReasons
                .getValue("schemaDecodeError"),
        )
    }

    @Test
    fun `decrypt in on mode falls back when native response is empty`() {
        val runtime = runtimeWithNativeResponse("""{"items":[]}""")

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }

        assertEquals("kotlin-result", result)
        assertEquals(1L, runtime.diagnostics().callFailures)
        assertEquals("native decrypt failed", runtime.diagnostics().lastError)
        assertEquals(
            1L,
            runtime
                .diagnostics()
                .componentStats
                .getValue("decrypt")
                .failureReasons
                .getValue("responseSizeMismatch"),
        )
    }

    @Test
    fun `decrypt in on mode falls back when native response reports error without leaking raw error`() {
        val runtime =
            runtimeWithNativeResponse(
                """{"items":[{"ok":false,"error":"ciphertext=secret userId=123 plaintext=leak"}]}""",
            )

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }
        val diagnostics = runtime.diagnostics()

        assertEquals("kotlin-result", result)
        assertEquals(1L, diagnostics.callFailures)
        assertEquals("native decrypt failed", diagnostics.lastError)
        assertEquals(
            1L,
            diagnostics.componentStats
                .getValue("decrypt")
                .failureReasons
                .getValue("itemError"),
        )
        assertEquals(
            1L,
            diagnostics.componentStats
                .getValue("decrypt")
                .fallbackReasons
                .getValue("itemError"),
        )
        assertFalse(diagnostics.lastError!!.contains("secret"))
        assertFalse(diagnostics.lastError.contains("123"))
        assertFalse(diagnostics.lastError.contains("leak"))
    }

    @Test
    fun `decrypt batch in on mode falls back for partial item error and records fallback items`() {
        val runtime =
            runtimeWithNativeResponse(
                """{"items":[{"ok":true,"plaintext":"rust-a"},{"ok":false,"errorKind":"invalidPadding","recoverable":true,"error":"ciphertext=secret"}]}""",
            )

        val result =
            runtime.decryptBatchOrFallback(
                items =
                    listOf(
                        NativeDecryptBatchItem(encType = 0, ciphertext = "cipher-a", userId = 1L),
                        NativeDecryptBatchItem(encType = 0, ciphertext = "cipher-b", userId = 2L),
                    ),
                kotlinDecryptBatch = { listOf("kotlin-a", "kotlin-b") },
            )
        val diagnostics = runtime.diagnostics()
        val decryptStats = diagnostics.componentStats.getValue("decrypt")

        assertEquals(listOf("kotlin-a", "kotlin-b"), result)
        assertEquals(1L, diagnostics.callFailures)
        assertEquals(1L, decryptStats.jniCalls)
        assertEquals(2L, decryptStats.items)
        assertEquals(2L, decryptStats.fallbacks)
        assertEquals(2L, decryptStats.fallbackReasons.getValue("itemError.invalidPadding"))
        assertEquals(1L, decryptStats.failureReasons.getValue("itemError.invalidPadding"))
        assertEquals("native decrypt failed", decryptStats.lastError)
        assertEquals("native decrypt failed", diagnostics.lastError)
    }

    @Test
    fun `decrypt in on mode falls back when native response is missing plaintext`() {
        val runtime = runtimeWithNativeResponse("""{"items":[{"ok":true}]}""")

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }

        assertEquals("kotlin-result", result)
        assertEquals(1L, runtime.diagnostics().callFailures)
        assertEquals("native decrypt failed", runtime.diagnostics().lastError)
    }

    private fun runtimeWithNativeResponse(response: String): NativeCoreRuntime =
        NativeCoreRuntime.create(
            env = mapOf("IRIS_NATIVE_CORE" to "on"),
            loader = {},
            jni = FakeJni(rawDecryptResponse = response),
        )

    private fun sampleRoutingCommand(): RoutingCommand =
        RoutingCommand(
            text = "!ping",
            room = "room",
            sender = "sender",
            userId = "42",
            sourceLogId = 100L,
        )

    private class FakeJni(
        private val selfTestResult: String = "iris-native-core:test",
        private val decryptResult: String = "kotlin-result",
        private val decryptResults: List<String>? = null,
        private val decryptError: RuntimeException? = null,
        private val rawDecryptResponse: String? = null,
        private val rawRoutingResponse: String? = null,
        private val rawParserResponse: String? = null,
        private val rawWebhookPayloadResponse: String? = null,
        private val rawIngressResponse: String? = null,
    ) : NativeCoreJniBridge {
        var lastDecryptRequest: ByteArray? = null
            private set
        var lastRoutingRequest: ByteArray? = null
            private set
        var lastParserRequest: ByteArray? = null
            private set
        var lastWebhookPayloadRequest: ByteArray? = null
            private set
        var lastIngressRequest: ByteArray? = null
            private set
        var decryptCalls = 0
            private set
        var routingCalls = 0
            private set
        var parserCalls = 0
            private set
        var webhookPayloadCalls = 0
            private set
        var ingressCalls = 0
            private set

        override fun nativeSelfTest(): String = selfTestResult

        override fun decryptBatch(requestJsonBytes: ByteArray): ByteArray {
            decryptCalls += 1
            lastDecryptRequest = requestJsonBytes
            decryptError?.let { throw it }
            return (rawDecryptResponse ?: successResponse(requestJsonBytes)).encodeToByteArray()
        }

        override fun routingBatch(requestJsonBytes: ByteArray): ByteArray {
            routingCalls += 1
            lastRoutingRequest = requestJsonBytes
            return (
                rawRoutingResponse
                    ?: """{"items":[{"ok":true,"kind":"NONE","normalizedText":""}]}"""
            ).encodeToByteArray()
        }

        override fun parserBatch(requestJsonBytes: ByteArray): ByteArray {
            parserCalls += 1
            lastParserRequest = requestJsonBytes
            return (
                rawParserResponse
                    ?: """{"items":[{"kind":"roomTitle","ok":true,"roomTitle":"native-title"}]}"""
            ).encodeToByteArray()
        }

        override fun webhookPayloadBatch(requestJsonBytes: ByteArray): ByteArray {
            webhookPayloadCalls += 1
            lastWebhookPayloadRequest = requestJsonBytes
            return (
                rawWebhookPayloadResponse
                    ?: """{"items":[{"ok":true,"payloadJson":"{\"route\":\"native\"}"}]}"""
            ).encodeToByteArray()
        }

        override fun ingressBatch(requestJsonBytes: ByteArray): ByteArray {
            ingressCalls += 1
            lastIngressRequest = requestJsonBytes
            return (
                rawIngressResponse
                    ?: """{"items":[{"ok":true,"kind":"NONE","normalizedText":""}]}"""
            ).encodeToByteArray()
        }

        private fun successResponse(requestJsonBytes: ByteArray): String {
            val itemCount = Regex(""""encType"""").findAll(requestJsonBytes.decodeToString()).count().coerceAtLeast(1)
            val results = decryptResults ?: List(itemCount) { decryptResult }
            val items =
                results.joinToString(separator = ",") { result ->
                    """{"ok":true,"plaintext":"$result"}"""
                }
            return """{"items":[$items]}"""
        }
    }
}
