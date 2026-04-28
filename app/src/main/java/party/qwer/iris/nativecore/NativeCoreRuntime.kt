package party.qwer.iris.nativecore

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import party.qwer.iris.CommandKind
import party.qwer.iris.IrisLogger
import party.qwer.iris.ParsedCommand
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.model.NoticeInfo
import party.qwer.iris.model.PeriodSpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class NativeCoreRuntime private constructor(
    private val config: NativeCoreModeConfig,
    private val jni: NativeCoreJniBridge,
    private val loaded: Boolean,
    private val selfTestResult: String?,
    private val loadError: String?,
) {
    private val nativeDecryptFailure = "native decrypt failed"
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    private val callFailures = AtomicLong(0)
    private val lastErrorRef = AtomicReference(loadError)
    private val componentStats =
        NativeCoreComponent.entries.associateWith { component ->
            NativeCoreComponentStats(config.effectiveMode(component))
        }
    private val nativeUsable: Boolean
        get() = loaded && selfTestResult != null

    fun diagnostics(): NativeCoreDiagnostics =
        NativeCoreDiagnostics(
            mode = config.mode.name.lowercase(),
            loaded = loaded,
            libraryPath = config.libraryPath,
            version = selfTestResult,
            enabledComponents =
                NativeCoreComponent.entries
                    .filter { component -> nativeUsable && config.effectiveMode(component) == NativeCoreMode.ON }
                    .map { it.id },
            selfTestOk = nativeUsable,
            callFailures = callFailures.get(),
            shadowMismatches =
                NativeCoreComponent.entries.associate { component ->
                    component.id to statsFor(component).shadowMismatches.get()
                },
            componentStats =
                NativeCoreComponent.entries.associate { component ->
                    component.id to statsFor(component).snapshot()
                },
            lastError = lastErrorRef.get(),
        )

    fun decryptOrFallback(
        encType: Int,
        ciphertext: String,
        userId: Long,
        kotlinDecrypt: () -> String,
    ): String =
        decryptBatchOrFallback(
            items = listOf(NativeDecryptBatchItem(encType, ciphertext, userId)),
            kotlinDecryptBatch = { listOf(kotlinDecrypt()) },
        ).single()

    fun decryptBatchOrFallback(
        items: List<NativeDecryptBatchItem>,
        kotlinDecryptBatch: () -> List<String>,
    ): List<String> {
        if (items.isEmpty()) return emptyList()
        val mode = config.effectiveMode(NativeCoreComponent.DECRYPT)
        if (!nativeUsable || mode == NativeCoreMode.OFF) {
            return kotlinDecryptBatch()
        }
        return when (mode) {
            NativeCoreMode.OFF -> kotlinDecryptBatch()
            NativeCoreMode.SHADOW -> decryptBatchShadow(items, kotlinDecryptBatch)
            NativeCoreMode.ON -> decryptBatchOn(items, kotlinDecryptBatch)
        }
    }

    fun parseCommandOrFallback(
        message: String,
        kotlinParse: () -> ParsedCommand,
    ): ParsedCommand =
        routingOrFallback(
            item = NativeRoutingBatchItem(text = message),
            commandRoutePrefixes = emptyMap(),
            imageMessageTypeRoutes = emptyMap(),
            kotlinDecision = { NativeRoutingDecision(parsedCommand = kotlinParse()) },
            shadowEquivalent = { kotlinResult, nativeResult ->
                kotlinResult.parsedCommand == nativeResult.parsedCommand
            },
        ).parsedCommand

    fun resolveWebhookRouteOrFallback(
        parsedCommand: ParsedCommand,
        commandRoutePrefixes: Map<String, List<String>>,
        kotlinResolve: () -> String?,
    ): String? =
        routingOrFallback(
            item = NativeRoutingBatchItem(text = parsedCommand.normalizedText),
            commandRoutePrefixes = commandRoutePrefixes,
            imageMessageTypeRoutes = emptyMap(),
            kotlinDecision = {
                NativeRoutingDecision(
                    parsedCommand = parsedCommand,
                    webhookRoute = kotlinResolve(),
                )
            },
            shadowEquivalent = { kotlinResult, nativeResult ->
                kotlinResult.webhookRoute == nativeResult.webhookRoute
            },
        ).webhookRoute

    fun resolveEventRouteOrFallback(
        messageType: String?,
        kotlinResolve: () -> String?,
    ): String? =
        routingOrFallback(
            item = NativeRoutingBatchItem(text = "", messageType = messageType),
            commandRoutePrefixes = emptyMap(),
            imageMessageTypeRoutes = emptyMap(),
            kotlinDecision = {
                NativeRoutingDecision(
                    parsedCommand = ParsedCommand(CommandKind.NONE, ""),
                    eventRoute = kotlinResolve(),
                )
            },
            shadowEquivalent = { kotlinResult, nativeResult ->
                kotlinResult.eventRoute == nativeResult.eventRoute
            },
        ).eventRoute

    fun resolveImageRouteOrFallback(
        messageType: String?,
        imageMessageTypeRoutes: Map<String, List<String>>,
        kotlinResolve: () -> String?,
    ): String? =
        routingOrFallback(
            item = NativeRoutingBatchItem(text = "", messageType = messageType),
            commandRoutePrefixes = emptyMap(),
            imageMessageTypeRoutes = imageMessageTypeRoutes,
            kotlinDecision = {
                NativeRoutingDecision(
                    parsedCommand = ParsedCommand(CommandKind.NONE, ""),
                    imageRoute = kotlinResolve(),
                )
            },
            shadowEquivalent = { kotlinResult, nativeResult ->
                kotlinResult.imageRoute == nativeResult.imageRoute
            },
        ).imageRoute

    fun parseRoomTitleOrFallback(
        meta: String?,
        kotlinParse: () -> String?,
    ): String? =
        parserOrFallback(
            item = NativeParserBatchItem(kind = "roomTitle", meta = meta),
            kotlinParse = kotlinParse,
            nativeValue = { it.roomTitle },
        )

    fun parseNoticesOrFallback(
        meta: String?,
        kotlinParse: () -> List<NoticeInfo>,
    ): List<NoticeInfo> =
        parserOrFallback(
            item = NativeParserBatchItem(kind = "notices", meta = meta),
            kotlinParse = kotlinParse,
            nativeValue = { result ->
                result.notices.map { notice ->
                    NoticeInfo(
                        content = notice.content,
                        authorId = notice.authorId,
                        updatedAt = notice.updatedAt,
                    )
                }
            },
        )

    fun parseIdArrayOrFallback(
        raw: String?,
        kotlinParse: () -> Set<Long>,
    ): Set<Long> =
        parserOrFallback(
            item = NativeParserBatchItem(kind = "idArray", raw = raw),
            kotlinParse = kotlinParse,
            nativeValue = { it.ids.toSet() },
        )

    fun parsePeriodSpecOrFallback(
        period: String?,
        defaultDays: Long,
        kotlinParse: () -> PeriodSpec,
    ): PeriodSpec =
        parserOrFallback(
            item = NativeParserBatchItem(kind = "periodSpec", period = period, defaultDays = defaultDays),
            kotlinParse = kotlinParse,
            nativeValue = { result ->
                when (result.periodSpec?.kind) {
                    "all" -> PeriodSpec.All
                    "days" -> PeriodSpec.Days(requireNotNull(result.periodSpec.days) { "native parsers failed" })
                    else -> error("native parsers failed")
                }
            },
        )

    fun buildWebhookPayloadOrFallback(
        command: RoutingCommand,
        route: String,
        messageId: String,
        kotlinBuild: () -> String,
    ): String {
        val mode = config.effectiveMode(NativeCoreComponent.WEBHOOK_PAYLOAD)
        if (!nativeUsable || mode == NativeCoreMode.OFF) {
            return kotlinBuild()
        }
        return when (mode) {
            NativeCoreMode.OFF -> kotlinBuild()
            NativeCoreMode.SHADOW -> {
                val kotlinPayload = kotlinBuild()
                val nativePayload =
                    runCatching { callNativeWebhookPayload(command, route, messageId) }
                        .onFailure { recordNativeFailure(NativeCoreComponent.WEBHOOK_PAYLOAD, 1) }
                        .getOrNull()
                if (nativePayload != null && !jsonEquivalent(kotlinPayload, nativePayload)) {
                    recordShadowMismatch(NativeCoreComponent.WEBHOOK_PAYLOAD, 1)
                }
                kotlinPayload
            }

            NativeCoreMode.ON ->
                runCatching { callNativeWebhookPayload(command, route, messageId) }
                    .getOrElse {
                        recordNativeFailure(NativeCoreComponent.WEBHOOK_PAYLOAD, 1)
                        kotlinBuild()
                    }
        }
    }

    private fun decryptBatchShadow(
        items: List<NativeDecryptBatchItem>,
        kotlinDecryptBatch: () -> List<String>,
    ): List<String> {
        val kotlinResults = kotlinDecryptBatch()
        val nativeResults =
            runCatching { decryptNativeBatch(items) }
                .onFailure { recordNativeFailure(NativeCoreComponent.DECRYPT, items.size) }
                .getOrNull()
        if (nativeResults != null) {
            compareDecryptShadowResults(kotlinResults, nativeResults)
        }
        return kotlinResults
    }

    private fun decryptBatchOn(
        items: List<NativeDecryptBatchItem>,
        kotlinDecryptBatch: () -> List<String>,
    ): List<String> =
        runCatching { decryptNativeBatch(items) }
            .getOrElse {
                recordNativeFailure(NativeCoreComponent.DECRYPT, items.size)
                kotlinDecryptBatch()
            }

    private fun compareDecryptShadowResults(
        kotlinResults: List<String>,
        nativeResults: List<String>,
    ) {
        val mismatchCount =
            if (kotlinResults.size != nativeResults.size) {
                maxOf(kotlinResults.size, nativeResults.size)
            } else {
                kotlinResults.zip(nativeResults).count { (kotlinResult, nativeResult) -> kotlinResult != nativeResult }
            }
        if (mismatchCount <= 0) return
        recordShadowMismatch(NativeCoreComponent.DECRYPT, mismatchCount)
    }

    private fun routingOrFallback(
        item: NativeRoutingBatchItem,
        commandRoutePrefixes: Map<String, List<String>>,
        imageMessageTypeRoutes: Map<String, List<String>>,
        kotlinDecision: () -> NativeRoutingDecision,
        shadowEquivalent: (NativeRoutingDecision, NativeRoutingDecision) -> Boolean,
    ): NativeRoutingDecision {
        val mode = config.effectiveMode(NativeCoreComponent.ROUTING)
        if (!nativeUsable || mode == NativeCoreMode.OFF) {
            return kotlinDecision()
        }
        return when (mode) {
            NativeCoreMode.OFF -> kotlinDecision()
            NativeCoreMode.SHADOW -> {
                val kotlinResult = kotlinDecision()
                val nativeResult =
                    runCatching { callNativeRouting(item, commandRoutePrefixes, imageMessageTypeRoutes) }
                        .onFailure { recordNativeFailure(NativeCoreComponent.ROUTING, 1) }
                        .getOrNull()
                if (nativeResult != null && !shadowEquivalent(kotlinResult, nativeResult)) {
                    recordShadowMismatch(NativeCoreComponent.ROUTING, 1)
                }
                kotlinResult
            }

            NativeCoreMode.ON ->
                runCatching { callNativeRouting(item, commandRoutePrefixes, imageMessageTypeRoutes) }
                    .getOrElse {
                        recordNativeFailure(NativeCoreComponent.ROUTING, 1)
                        kotlinDecision()
                    }
        }
    }

    private fun callNativeRouting(
        item: NativeRoutingBatchItem,
        commandRoutePrefixes: Map<String, List<String>>,
        imageMessageTypeRoutes: Map<String, List<String>>,
    ): NativeRoutingDecision {
        val stats = statsFor(NativeCoreComponent.ROUTING)
        stats.jniCalls.incrementAndGet()
        stats.items.incrementAndGet()
        val request =
            NativeRoutingBatchRequest(
                items = listOf(item),
                commandRoutePrefixes = commandRoutePrefixes.toNativeRouteEntries(),
                imageMessageTypeRoutes = imageMessageTypeRoutes.toNativeRouteEntries(),
            )
        val rawResponse = jni.routingBatch(json.encodeToString(request).encodeToByteArray()).decodeToString()
        val response = json.decodeFromString<NativeRoutingBatchResponse>(rawResponse)
        val result = response.items.singleOrNull() ?: error("native routing failed")
        if (!result.ok) error("native routing failed")
        return NativeRoutingDecision(
            parsedCommand =
                ParsedCommand(
                    kind =
                        when (result.kind) {
                            "NONE" -> CommandKind.NONE
                            "COMMENT" -> CommandKind.COMMENT
                            "WEBHOOK" -> CommandKind.WEBHOOK
                            else -> error("native routing failed")
                        },
                    normalizedText = result.normalizedText,
                ),
            webhookRoute = result.webhookRoute,
            eventRoute = result.eventRoute,
            imageRoute = result.imageRoute,
            targetRoute = result.targetRoute,
        )
    }

    private fun <T> parserOrFallback(
        item: NativeParserBatchItem,
        kotlinParse: () -> T,
        nativeValue: (NativeParserBatchResult) -> T,
    ): T {
        val mode = config.effectiveMode(NativeCoreComponent.PARSERS)
        if (!nativeUsable || mode == NativeCoreMode.OFF) {
            return kotlinParse()
        }
        return when (mode) {
            NativeCoreMode.OFF -> kotlinParse()
            NativeCoreMode.SHADOW -> {
                val kotlinResult = kotlinParse()
                val nativeResult =
                    runCatching { callNativeParser(item) }
                        .onFailure { recordNativeFailure(NativeCoreComponent.PARSERS, 1, item.kind) }
                        .getOrNull()
                if (nativeResult != null) {
                    if (nativeResult.fallback) {
                        recordComponentFallback(NativeCoreComponent.PARSERS, 1, item.kind)
                    }
                    val converted = runCatching { nativeValue(nativeResult) }.getOrNull()
                    if (converted != kotlinResult) {
                        recordShadowMismatch(NativeCoreComponent.PARSERS, 1, item.kind)
                    }
                }
                kotlinResult
            }

            NativeCoreMode.ON -> {
                val nativeResult =
                    runCatching { callNativeParser(item) }
                        .getOrElse {
                            recordNativeFailure(NativeCoreComponent.PARSERS, 1, item.kind)
                            return kotlinParse()
                        }
                if (nativeResult.fallback) {
                    recordComponentFallback(NativeCoreComponent.PARSERS, 1, item.kind)
                    kotlinParse()
                } else {
                    runCatching { nativeValue(nativeResult) }
                        .getOrElse {
                            recordNativeFailure(NativeCoreComponent.PARSERS, 1, item.kind)
                            kotlinParse()
                        }
                }
            }
        }
    }

    private fun callNativeParser(item: NativeParserBatchItem): NativeParserBatchResult {
        val stats = statsFor(NativeCoreComponent.PARSERS)
        stats.jniCalls.incrementAndGet()
        stats.items.incrementAndGet()
        val request = NativeParserBatchRequest(items = listOf(item))
        val rawResponse = jni.parserBatch(json.encodeToString(request).encodeToByteArray()).decodeToString()
        val response = json.decodeFromString<NativeParserBatchResponse>(rawResponse)
        val result = response.items.singleOrNull() ?: error("native parsers failed")
        if (!result.ok) error("native parsers failed")
        return result
    }

    private fun callNativeWebhookPayload(
        command: RoutingCommand,
        route: String,
        messageId: String,
    ): String {
        val stats = statsFor(NativeCoreComponent.WEBHOOK_PAYLOAD)
        stats.jniCalls.incrementAndGet()
        stats.items.incrementAndGet()
        val request =
            NativeWebhookPayloadBatchRequest(
                items =
                    listOf(
                        NativeWebhookPayloadBatchItem(
                            command =
                                NativeWebhookCommand(
                                    text = command.text,
                                    room = command.room,
                                    sender = command.sender,
                                    userId = command.userId,
                                    sourceLogId = command.sourceLogId,
                                    chatLogId = command.chatLogId,
                                    roomType = command.roomType,
                                    roomLinkId = command.roomLinkId,
                                    threadId = command.threadId,
                                    threadScope = command.threadScope,
                                    messageType = command.messageType,
                                    attachment = command.attachment,
                                    eventPayload = command.eventPayload,
                                ),
                            route = route,
                            messageId = messageId,
                        ),
                    ),
            )
        val rawResponse = jni.webhookPayloadBatch(json.encodeToString(request).encodeToByteArray()).decodeToString()
        val response = json.decodeFromString<NativeWebhookPayloadBatchResponse>(rawResponse)
        val result = response.items.singleOrNull() ?: error("native webhook payload failed")
        if (!result.ok) error("native webhook payload failed")
        return result.payloadJson ?: error("native webhook payload failed")
    }

    private fun recordShadowMismatch(
        component: NativeCoreComponent,
        mismatchCount: Int,
        detailKey: String? = null,
    ) {
        if (mismatchCount <= 0) return
        statsFor(component).recordShadowMismatch(mismatchCount, detailKey)
        IrisLogger.warn("[NativeCore] shadow mismatch component=${component.id} count=$mismatchCount")
    }

    private fun recordComponentFallback(
        component: NativeCoreComponent,
        fallbackItems: Int,
        detailKey: String? = null,
    ) {
        statsFor(component).recordFallback(fallbackItems, detailKey)
    }

    private fun recordNativeFailure(
        component: NativeCoreComponent,
        fallbackItems: Int,
        detailKey: String? = null,
    ) {
        val message =
            when (component) {
                NativeCoreComponent.DECRYPT -> nativeDecryptFailure
                NativeCoreComponent.ROUTING -> "native routing failed"
                NativeCoreComponent.PARSERS -> "native parsers failed"
                NativeCoreComponent.WEBHOOK_PAYLOAD -> "native webhook payload failed"
            }
        val stats = statsFor(component)
        callFailures.incrementAndGet()
        stats.recordFallback(fallbackItems, detailKey)
        stats.lastError.set(message)
        lastErrorRef.set(message)
        IrisLogger.error("[NativeCore] native call failed: $message")
    }

    private fun decryptNativeBatch(items: List<NativeDecryptBatchItem>): List<String> {
        val stats = statsFor(NativeCoreComponent.DECRYPT)
        stats.jniCalls.incrementAndGet()
        stats.items.addAndGet(items.size.toLong())
        val request = DecryptBatchRequest(items.map { DecryptBatchItem(it.encType, it.ciphertext, it.userId) })
        val rawResponse = jni.decryptBatch(json.encodeToString(request).encodeToByteArray()).decodeToString()
        val response = json.decodeFromString<DecryptBatchResponse>(rawResponse)
        if (response.items.size != items.size) {
            error(nativeDecryptFailure)
        }
        return response.items.map { item ->
            if (!item.ok) error(nativeDecryptFailure)
            item.plaintext ?: error(nativeDecryptFailure)
        }
    }

    private fun statsFor(component: NativeCoreComponent): NativeCoreComponentStats = componentStats.getValue(component)

    private fun Map<String, List<String>>.toNativeRouteEntries(): List<NativeRouteEntry> = entries.map { (route, values) -> NativeRouteEntry(route = route, values = values) }

    private fun jsonEquivalent(
        first: String,
        second: String,
    ): Boolean =
        runCatching { json.parseToJsonElement(first) == json.parseToJsonElement(second) }
            .getOrElse { first == second }

    private class NativeCoreComponentStats(
        private val mode: NativeCoreMode,
    ) {
        val jniCalls = AtomicLong(0)
        val items = AtomicLong(0)
        val fallbacks = AtomicLong(0)
        val shadowMismatches = AtomicLong(0)
        val lastError = AtomicReference<String?>(null)
        private val fallbacksByKey = ConcurrentHashMap<String, AtomicLong>()
        private val shadowMismatchesByKey = ConcurrentHashMap<String, AtomicLong>()

        fun recordFallback(
            count: Int,
            detailKey: String?,
        ) {
            if (count <= 0) return
            fallbacks.addAndGet(count.toLong())
            detailKey?.let { key ->
                fallbacksByKey.computeIfAbsent(key) { AtomicLong(0) }.addAndGet(count.toLong())
            }
        }

        fun recordShadowMismatch(
            count: Int,
            detailKey: String?,
        ) {
            if (count <= 0) return
            shadowMismatches.addAndGet(count.toLong())
            detailKey?.let { key ->
                shadowMismatchesByKey.computeIfAbsent(key) { AtomicLong(0) }.addAndGet(count.toLong())
            }
        }

        fun snapshot(): NativeCoreComponentDiagnostics =
            NativeCoreComponentDiagnostics(
                mode = mode.name.lowercase(),
                jniCalls = jniCalls.get(),
                items = items.get(),
                fallbacks = fallbacks.get(),
                shadowMismatches = shadowMismatches.get(),
                fallbacksByKey = fallbacksByKey.snapshotCounts(),
                shadowMismatchesByKey = shadowMismatchesByKey.snapshotCounts(),
                lastError = lastError.get(),
            )
    }

    companion object {
        fun create(
            env: Map<String, String> = System.getenv(),
            loader: (String) -> Unit = System::load,
            jni: NativeCoreJniBridge = NativeCoreJni,
        ): NativeCoreRuntime {
            val config = NativeCoreModeConfig.fromEnv(env)
            config.parseWarning?.let { IrisLogger.warn("[NativeCore] $it") }
            if (!config.requiresLoad) {
                return NativeCoreRuntime(config, jni, loaded = false, selfTestResult = null, loadError = null)
            }
            return runCatching {
                loader(config.libraryPath)
                val selfTest = jni.nativeSelfTest()
                if (selfTest.startsWith(nativeSelfTestErrorPrefix)) {
                    IrisLogger.error("[NativeCore] native self-test failed: $nativeSelfTestFailure")
                    NativeCoreRuntime(
                        config,
                        jni,
                        loaded = true,
                        selfTestResult = null,
                        loadError = nativeSelfTestFailure,
                    )
                } else {
                    NativeCoreRuntime(config, jni, loaded = true, selfTestResult = selfTest, loadError = null)
                }
            }.getOrElse { error ->
                IrisLogger.error("[NativeCore] failed to load native core: ${error.message}", error)
                NativeCoreRuntime(
                    config = config,
                    jni = jni,
                    loaded = false,
                    selfTestResult = null,
                    loadError = error.message ?: error::class.java.simpleName,
                )
            }
        }

        private const val nativeSelfTestErrorPrefix = "error:"
        private const val nativeSelfTestFailure = "native core self-test failed"
    }
}

private fun ConcurrentHashMap<String, AtomicLong>.snapshotCounts(): Map<String, Long> =
    entries
        .associate { (key, value) -> key to value.get() }
        .toSortedMap()
