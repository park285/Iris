package party.qwer.iris.nativecore

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import party.qwer.iris.CommandKind
import party.qwer.iris.IrisLogger
import party.qwer.iris.ParsedCommand
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.buildRoutingMessageId
import party.qwer.iris.delivery.webhook.buildWebhookPayloadKotlin
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

    fun parseCommandBatchOrFallback(
        messages: List<String>,
        kotlinParseBatch: () -> List<ParsedCommand>,
    ): List<ParsedCommand> {
        if (messages.isEmpty()) return emptyList()
        val mode = config.effectiveMode(NativeCoreComponent.ROUTING)
        if (!nativeUsable || mode == NativeCoreMode.OFF) {
            return kotlinParseBatch()
        }
        val items = messages.map { message -> NativeRoutingBatchItem(text = message) }
        return when (mode) {
            NativeCoreMode.OFF -> kotlinParseBatch()
            NativeCoreMode.SHADOW -> {
                val kotlinResults = kotlinParseBatch()
                val nativeResults =
                    runCatching {
                        callNativeRoutingBatch(
                            items = items,
                            commandRoutePrefixes = emptyMap(),
                            imageMessageTypeRoutes = emptyMap(),
                        )
                    }.onFailure { recordNativeFailure(NativeCoreComponent.ROUTING, items.size) }
                        .getOrNull()
                if (nativeResults != null) {
                    val mismatchCount =
                        compareByIndex(kotlinResults, nativeResults.map { it.parsedCommand }) { kotlinResult, nativeResult ->
                            kotlinResult == nativeResult
                        }
                    recordShadowMismatch(NativeCoreComponent.ROUTING, mismatchCount)
                }
                kotlinResults
            }

            NativeCoreMode.ON ->
                runCatching {
                    callNativeRoutingBatch(
                        items = items,
                        commandRoutePrefixes = emptyMap(),
                        imageMessageTypeRoutes = emptyMap(),
                    ).map { it.parsedCommand }
                }.getOrElse {
                    recordNativeFailure(NativeCoreComponent.ROUTING, items.size)
                    kotlinParseBatch()
                }
        }
    }

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

    fun parseRoomTitlesOrFallback(
        metas: List<String?>,
        kotlinParseBatch: () -> List<String?>,
    ): List<String?> =
        parserBatchOrFallback(
            items = metas.map { meta -> NativeParserBatchItem(kind = "roomTitle", meta = meta) },
            kotlinParseBatch = kotlinParseBatch,
            nativeValue = { result -> result.roomTitle },
            detailKey = "roomTitle",
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
    ): NativeRoutingDecision =
        callNativeRoutingBatch(
            items = listOf(item),
            commandRoutePrefixes = commandRoutePrefixes,
            imageMessageTypeRoutes = imageMessageTypeRoutes,
        ).singleOrNull() ?: error("native routing failed")

    private fun callNativeRoutingBatch(
        items: List<NativeRoutingBatchItem>,
        commandRoutePrefixes: Map<String, List<String>>,
        imageMessageTypeRoutes: Map<String, List<String>>,
    ): List<NativeRoutingDecision> {
        if (items.isEmpty()) return emptyList()
        val stats = statsFor(NativeCoreComponent.ROUTING)
        stats.jniCalls.incrementAndGet()
        stats.items.addAndGet(items.size.toLong())
        val startedAt = System.nanoTime()
        return try {
            val request =
                NativeRoutingBatchRequest(
                    items = items,
                    commandRoutePrefixes = commandRoutePrefixes.toNativeRouteEntries(),
                    imageMessageTypeRoutes = imageMessageTypeRoutes.toNativeRouteEntries(),
                )
            val rawResponse = jni.routingBatch(json.encodeToString(request).encodeToByteArray()).decodeToString()
            val response = json.decodeFromString<NativeRoutingBatchResponse>(rawResponse)
            if (response.items.size != items.size) {
                error("native routing failed")
            }
            response.items.map { result ->
                if (!result.ok) error("native routing failed")
                NativeRoutingDecision(
                    parsedCommand = result.toParsedCommand(),
                    webhookRoute = result.webhookRoute,
                    eventRoute = result.eventRoute,
                    imageRoute = result.imageRoute,
                    targetRoute = result.targetRoute,
                )
            }
        } finally {
            stats.recordNativeDuration(System.nanoTime() - startedAt)
        }
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

    fun planIngressBatchOrFallback(
        commands: List<RoutingCommand>,
        commandRoutePrefixes: Map<String, List<String>>,
        imageMessageTypeRoutes: Map<String, List<String>>,
        kotlinPlanBatch: () -> List<NativeIngressPlan>,
    ): List<NativeIngressPlan> {
        if (commands.isEmpty()) return emptyList()
        val routingMode = config.effectiveMode(NativeCoreComponent.ROUTING)
        val payloadMode = config.effectiveMode(NativeCoreComponent.WEBHOOK_PAYLOAD)
        if (!nativeUsable || (routingMode == NativeCoreMode.OFF && payloadMode == NativeCoreMode.OFF)) {
            return kotlinPlanBatch()
        }

        if (routingMode == NativeCoreMode.ON && payloadMode == NativeCoreMode.ON) {
            return runCatching { callNativeIngressBatch(commands, commandRoutePrefixes, imageMessageTypeRoutes) }
                .getOrElse {
                    recordNativeIngressFailure(commands.size)
                    kotlinPlanBatch()
                }
        }

        var kotlinPlans: List<NativeIngressPlan>? = null

        fun kotlinPlans(): List<NativeIngressPlan> {
            val existing = kotlinPlans
            if (existing != null) return existing
            return kotlinPlanBatch().also { kotlinPlans = it }
        }

        var plans: List<NativeIngressPlan>? = null
        if (routingMode == NativeCoreMode.OFF) {
            plans = kotlinPlans()
        } else {
            val nativeRoutingPlans =
                runCatching {
                    val items =
                        commands.map { command ->
                            NativeRoutingBatchItem(text = command.text, messageType = command.messageType)
                        }
                    callNativeRoutingBatch(items, commandRoutePrefixes, imageMessageTypeRoutes)
                        .toIngressPlans(commands)
                }.onFailure { recordNativeFailure(NativeCoreComponent.ROUTING, commands.size) }
                    .getOrNull()
            when (routingMode) {
                NativeCoreMode.OFF -> plans = kotlinPlans()
                NativeCoreMode.SHADOW -> {
                    val currentKotlinPlans = kotlinPlans()
                    nativeRoutingPlans?.let { compareRoutingShadowResults(currentKotlinPlans, it) }
                    plans = currentKotlinPlans
                }

                NativeCoreMode.ON -> {
                    plans = nativeRoutingPlans ?: kotlinPlans()
                }
            }
        }

        val routedPlans = plans
        return when (payloadMode) {
            NativeCoreMode.OFF -> completeKotlinPayloadPlans(commands, routedPlans)
            NativeCoreMode.SHADOW -> shadowWebhookPayloadBatch(commands, routedPlans)
            NativeCoreMode.ON -> buildNativeWebhookPayloadPlansOrFallback(commands, routedPlans)
        }
    }

    private fun <T> parserBatchOrFallback(
        items: List<NativeParserBatchItem>,
        kotlinParseBatch: () -> List<T>,
        nativeValue: (NativeParserBatchResult) -> T,
        detailKey: String,
    ): List<T> {
        if (items.isEmpty()) return emptyList()
        val mode = config.effectiveMode(NativeCoreComponent.PARSERS)
        if (!nativeUsable || mode == NativeCoreMode.OFF) {
            return kotlinParseBatch()
        }
        return when (mode) {
            NativeCoreMode.OFF -> kotlinParseBatch()
            NativeCoreMode.SHADOW -> {
                val kotlinResults = kotlinParseBatch()
                val nativeResults =
                    runCatching { callNativeParserBatch(items) }
                        .onFailure { recordNativeFailure(NativeCoreComponent.PARSERS, items.size, detailKey) }
                        .getOrNull()
                if (nativeResults != null) {
                    val fallbackCount = nativeResults.count { result -> result.fallback }
                    recordComponentFallback(NativeCoreComponent.PARSERS, fallbackCount, detailKey)
                    val nativeConverted = nativeResults.map { result -> runCatching { nativeValue(result) }.getOrNull() }
                    val mismatchCount =
                        if (kotlinResults.size != nativeConverted.size) {
                            maxOf(kotlinResults.size, nativeConverted.size)
                        } else {
                            kotlinResults.zip(nativeConverted).count { (kotlinResult, nativeResult) -> kotlinResult != nativeResult }
                        }
                    recordShadowMismatch(NativeCoreComponent.PARSERS, mismatchCount, detailKey)
                }
                kotlinResults
            }

            NativeCoreMode.ON -> {
                val nativeResults =
                    runCatching { callNativeParserBatch(items) }
                        .getOrElse {
                            recordNativeFailure(NativeCoreComponent.PARSERS, items.size, detailKey)
                            return kotlinParseBatch()
                        }
                val fallbackCount = nativeResults.count { result -> result.fallback }
                if (fallbackCount > 0) {
                    recordComponentFallback(NativeCoreComponent.PARSERS, fallbackCount, detailKey)
                    return kotlinParseBatch()
                }
                runCatching { nativeResults.map(nativeValue) }
                    .getOrElse {
                        recordNativeFailure(NativeCoreComponent.PARSERS, items.size, detailKey)
                        kotlinParseBatch()
                    }
            }
        }
    }

    private fun callNativeParser(item: NativeParserBatchItem): NativeParserBatchResult = callNativeParserBatch(listOf(item)).singleOrNull() ?: error("native parsers failed")

    private fun callNativeParserBatch(items: List<NativeParserBatchItem>): List<NativeParserBatchResult> {
        if (items.isEmpty()) return emptyList()
        val stats = statsFor(NativeCoreComponent.PARSERS)
        stats.jniCalls.incrementAndGet()
        stats.items.addAndGet(items.size.toLong())
        val startedAt = System.nanoTime()
        return try {
            val request = NativeParserBatchRequest(items = items)
            val rawResponse = jni.parserBatch(json.encodeToString(request).encodeToByteArray()).decodeToString()
            val response = json.decodeFromString<NativeParserBatchResponse>(rawResponse)
            if (response.items.size != items.size) {
                error("native parsers failed")
            }
            response.items.map { result ->
                if (!result.ok) error("native parsers failed")
                result
            }
        } finally {
            stats.recordNativeDuration(System.nanoTime() - startedAt)
        }
    }

    private fun callNativeWebhookPayload(
        command: RoutingCommand,
        route: String,
        messageId: String,
    ): String =
        callNativeWebhookPayloadBatch(
            listOf(
                NativeWebhookPayloadBatchItem(
                    command = command.toNativeWebhookCommand(),
                    route = route,
                    messageId = messageId,
                ),
            ),
        ).singleOrNull() ?: error("native webhook payload failed")

    private fun callNativeWebhookPayloadBatch(items: List<NativeWebhookPayloadBatchItem>): List<String> {
        if (items.isEmpty()) return emptyList()
        val stats = statsFor(NativeCoreComponent.WEBHOOK_PAYLOAD)
        stats.jniCalls.incrementAndGet()
        stats.items.addAndGet(items.size.toLong())
        val startedAt = System.nanoTime()
        return try {
            val request = NativeWebhookPayloadBatchRequest(items = items)
            val rawResponse = jni.webhookPayloadBatch(json.encodeToString(request).encodeToByteArray()).decodeToString()
            val response = json.decodeFromString<NativeWebhookPayloadBatchResponse>(rawResponse)
            if (response.items.size != items.size) {
                error("native webhook payload failed")
            }
            response.items.map { result ->
                if (!result.ok) error("native webhook payload failed")
                result.payloadJson ?: error("native webhook payload failed")
            }
        } finally {
            stats.recordNativeDuration(System.nanoTime() - startedAt)
        }
    }

    private fun callNativeIngressBatch(
        commands: List<RoutingCommand>,
        commandRoutePrefixes: Map<String, List<String>>,
        imageMessageTypeRoutes: Map<String, List<String>>,
    ): List<NativeIngressPlan> {
        if (commands.isEmpty()) return emptyList()
        val routingStats = statsFor(NativeCoreComponent.ROUTING)
        val payloadStats = statsFor(NativeCoreComponent.WEBHOOK_PAYLOAD)
        routingStats.jniCalls.incrementAndGet()
        payloadStats.jniCalls.incrementAndGet()
        routingStats.items.addAndGet(commands.size.toLong())
        val startedAt = System.nanoTime()
        return try {
            val request =
                NativeIngressBatchRequest(
                    items = commands.map { command -> NativeIngressBatchItem(command = command.toNativeWebhookCommand()) },
                    commandRoutePrefixes = commandRoutePrefixes.toNativeRouteEntries(),
                    imageMessageTypeRoutes = imageMessageTypeRoutes.toNativeRouteEntries(),
                )
            val rawResponse = jni.ingressBatch(json.encodeToString(request).encodeToByteArray()).decodeToString()
            val response = json.decodeFromString<NativeIngressBatchResponse>(rawResponse)
            if (response.items.size != commands.size) {
                error("native ingress failed")
            }
            response.items
                .map { result ->
                    if (!result.ok) error("native ingress failed")
                    val targetRoute = result.targetRoute
                    if (targetRoute != null && (result.messageId.isNullOrBlank() || result.payloadJson == null)) {
                        error("native ingress failed")
                    }
                    NativeIngressPlan(
                        parsedCommand = result.toParsedCommand(),
                        targetRoute = targetRoute,
                        messageId = result.messageId,
                        payloadJson = result.payloadJson,
                    )
                }.also { plans ->
                    payloadStats.items.addAndGet(plans.count { plan -> plan.targetRoute != null }.toLong())
                }
        } finally {
            val elapsed = System.nanoTime() - startedAt
            routingStats.recordNativeDuration(elapsed)
            payloadStats.recordNativeDuration(elapsed)
        }
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

    private fun recordNativeIngressFailure(fallbackItems: Int) {
        val message = "native ingress failed"
        callFailures.incrementAndGet()
        listOf(NativeCoreComponent.ROUTING, NativeCoreComponent.WEBHOOK_PAYLOAD).forEach { component ->
            val stats = statsFor(component)
            stats.recordFallback(fallbackItems, null)
            stats.lastError.set(message)
        }
        lastErrorRef.set(message)
        IrisLogger.error("[NativeCore] native call failed: $message")
    }

    private fun decryptNativeBatch(items: List<NativeDecryptBatchItem>): List<String> {
        val stats = statsFor(NativeCoreComponent.DECRYPT)
        stats.jniCalls.incrementAndGet()
        stats.items.addAndGet(items.size.toLong())
        val startedAt = System.nanoTime()
        return try {
            val request = DecryptBatchRequest(items.map { DecryptBatchItem(it.encType, it.ciphertext, it.userId) })
            val rawResponse = jni.decryptBatch(json.encodeToString(request).encodeToByteArray()).decodeToString()
            val response = json.decodeFromString<DecryptBatchResponse>(rawResponse)
            if (response.items.size != items.size) {
                error(nativeDecryptFailure)
            }
            response.items.map { item ->
                if (!item.ok) error(nativeDecryptFailure)
                item.plaintext ?: error(nativeDecryptFailure)
            }
        } finally {
            stats.recordNativeDuration(System.nanoTime() - startedAt)
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

    private fun compareIngressShadowResults(
        kotlinPlans: List<NativeIngressPlan>,
        nativePlans: List<NativeIngressPlan>,
    ) {
        val routingMismatches =
            compareByIndex(kotlinPlans, nativePlans) { kotlinPlan, nativePlan ->
                kotlinPlan.parsedCommand == nativePlan.parsedCommand &&
                    kotlinPlan.targetRoute == nativePlan.targetRoute &&
                    kotlinPlan.messageId == nativePlan.messageId
            }
        recordShadowMismatch(NativeCoreComponent.ROUTING, routingMismatches)

        val payloadMismatches =
            compareByIndex(kotlinPlans, nativePlans) { kotlinPlan, nativePlan ->
                val kotlinPayload = kotlinPlan.payloadJson
                val nativePayload = nativePlan.payloadJson
                when {
                    kotlinPayload == null && nativePayload == null -> true
                    kotlinPayload == null || nativePayload == null -> false
                    else -> jsonEquivalent(kotlinPayload, nativePayload)
                }
            }
        recordShadowMismatch(NativeCoreComponent.WEBHOOK_PAYLOAD, payloadMismatches)
    }

    private fun compareRoutingShadowResults(
        kotlinPlans: List<NativeIngressPlan>,
        nativePlans: List<NativeIngressPlan>,
    ) {
        val routingMismatches =
            compareByIndex(kotlinPlans, nativePlans) { kotlinPlan, nativePlan ->
                kotlinPlan.parsedCommand == nativePlan.parsedCommand &&
                    kotlinPlan.targetRoute == nativePlan.targetRoute
            }
        recordShadowMismatch(NativeCoreComponent.ROUTING, routingMismatches)
    }

    private fun completeKotlinPayloadPlans(
        commands: List<RoutingCommand>,
        plans: List<NativeIngressPlan>,
    ): List<NativeIngressPlan> =
        commands.zip(plans).map { (command, plan) ->
            val route = plan.targetRoute ?: return@map plan
            val normalizedCommand = command.copy(text = plan.parsedCommand.normalizedText)
            val messageId = plan.messageId ?: buildRoutingMessageId(normalizedCommand, route)
            plan.copy(
                messageId = messageId,
                payloadJson = plan.payloadJson ?: buildWebhookPayloadKotlin(normalizedCommand, route, messageId),
            )
        }

    private fun shadowWebhookPayloadBatch(
        commands: List<RoutingCommand>,
        plans: List<NativeIngressPlan>,
    ): List<NativeIngressPlan> {
        val kotlinPayloadPlans = completeKotlinPayloadPlans(commands, plans)
        val inputs = payloadBatchInputs(commands, kotlinPayloadPlans)
        if (inputs.isEmpty()) return kotlinPayloadPlans
        val nativePayloads =
            runCatching { callNativeWebhookPayloadBatch(inputs.map { it.value }) }
                .onFailure { recordNativeFailure(NativeCoreComponent.WEBHOOK_PAYLOAD, inputs.size) }
                .getOrNull()
        if (nativePayloads != null) {
            val mismatchCount =
                inputs.zip(nativePayloads).count { (input, nativePayload) ->
                    val kotlinPayload = kotlinPayloadPlans[input.index].payloadJson
                    kotlinPayload == null || !jsonEquivalent(kotlinPayload, nativePayload)
                }
            recordShadowMismatch(NativeCoreComponent.WEBHOOK_PAYLOAD, mismatchCount)
        }
        return kotlinPayloadPlans
    }

    private fun buildNativeWebhookPayloadPlansOrFallback(
        commands: List<RoutingCommand>,
        plans: List<NativeIngressPlan>,
    ): List<NativeIngressPlan> {
        val inputs = payloadBatchInputs(commands, plans)
        if (inputs.isEmpty()) return plans
        return runCatching {
            val nativePayloads = callNativeWebhookPayloadBatch(inputs.map { it.value })
            plans.toMutableList().also { mutablePlans ->
                inputs.zip(nativePayloads).forEach { (input, nativePayload) ->
                    mutablePlans[input.index] =
                        mutablePlans[input.index].copy(
                            messageId = input.value.messageId,
                            payloadJson = nativePayload,
                        )
                }
            }
        }.getOrElse {
            recordNativeFailure(NativeCoreComponent.WEBHOOK_PAYLOAD, inputs.size)
            completeKotlinPayloadPlans(commands, plans)
        }
    }

    private fun payloadBatchInputs(
        commands: List<RoutingCommand>,
        plans: List<NativeIngressPlan>,
    ): List<IndexedValue<NativeWebhookPayloadBatchItem>> =
        commands.zip(plans).mapIndexedNotNull { index, (command, plan) ->
            val route = plan.targetRoute ?: return@mapIndexedNotNull null
            val normalizedCommand = command.copy(text = plan.parsedCommand.normalizedText)
            val messageId = plan.messageId ?: buildRoutingMessageId(normalizedCommand, route)
            IndexedValue(
                index,
                NativeWebhookPayloadBatchItem(
                    command = normalizedCommand.toNativeWebhookCommand(),
                    route = route,
                    messageId = messageId,
                ),
            )
        }

    private fun List<NativeRoutingDecision>.toIngressPlans(commands: List<RoutingCommand>): List<NativeIngressPlan> =
        commands.zip(this).map { (command, decision) ->
            val targetRoute = decision.targetRoute
            val parsedCommand = decision.parsedCommand
            NativeIngressPlan(
                parsedCommand = parsedCommand,
                targetRoute = targetRoute,
                messageId =
                    targetRoute?.let { route ->
                        buildRoutingMessageId(command.copy(text = parsedCommand.normalizedText), route)
                    },
            )
        }

    private fun <T> compareByIndex(
        expected: List<T>,
        actual: List<T>,
        equivalent: (T, T) -> Boolean,
    ): Int {
        if (expected.size != actual.size) return maxOf(expected.size, actual.size)
        return expected.zip(actual).count { (left, right) -> !equivalent(left, right) }
    }

    private class NativeCoreComponentStats(
        private val mode: NativeCoreMode,
    ) {
        val jniCalls = AtomicLong(0)
        val items = AtomicLong(0)
        val fallbacks = AtomicLong(0)
        val shadowMismatches = AtomicLong(0)
        val totalNativeNanos = AtomicLong(0)
        val maxNativeNanos = AtomicLong(0)
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

        fun recordNativeDuration(nanos: Long) {
            if (nanos <= 0L) return
            totalNativeNanos.addAndGet(nanos)
            maxNativeNanos.updateAndGet { current -> maxOf(current, nanos) }
        }

        fun snapshot(): NativeCoreComponentDiagnostics {
            val callCount = jniCalls.get()
            val totalMicros = totalNativeNanos.get().nanosToMicros()
            return NativeCoreComponentDiagnostics(
                mode = mode.name.lowercase(),
                jniCalls = callCount,
                items = items.get(),
                fallbacks = fallbacks.get(),
                shadowMismatches = shadowMismatches.get(),
                totalNativeMicros = totalMicros,
                maxNativeMicros = maxNativeNanos.get().nanosToMicros(),
                averageNativeMicros = if (callCount > 0L) totalMicros / callCount else 0L,
                fallbacksByKey = fallbacksByKey.snapshotCounts(),
                shadowMismatchesByKey = shadowMismatchesByKey.snapshotCounts(),
                lastError = lastError.get(),
            )
        }
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

private fun Long.nanosToMicros(): Long = (this / 1_000L).coerceAtLeast(if (this > 0L) 1L else 0L)

private fun NativeRoutingBatchResult.toParsedCommand(): ParsedCommand =
    ParsedCommand(
        kind =
            when (kind) {
                "NONE" -> CommandKind.NONE
                "COMMENT" -> CommandKind.COMMENT
                "WEBHOOK" -> CommandKind.WEBHOOK
                else -> error("native routing failed")
            },
        normalizedText = normalizedText,
    )

private fun NativeIngressBatchResult.toParsedCommand(): ParsedCommand =
    ParsedCommand(
        kind =
            when (kind) {
                "NONE" -> CommandKind.NONE
                "COMMENT" -> CommandKind.COMMENT
                "WEBHOOK" -> CommandKind.WEBHOOK
                else -> error("native ingress failed")
            },
        normalizedText = normalizedText,
    )

private fun RoutingCommand.toNativeWebhookCommand(): NativeWebhookCommand =
    NativeWebhookCommand(
        text = text,
        room = room,
        sender = sender,
        userId = userId,
        sourceLogId = sourceLogId,
        chatLogId = chatLogId,
        roomType = roomType,
        roomLinkId = roomLinkId,
        threadId = threadId,
        threadScope = threadScope,
        messageType = messageType,
        attachment = attachment,
        eventPayload = eventPayload,
    )

private fun ConcurrentHashMap<String, AtomicLong>.snapshotCounts(): Map<String, Long> =
    entries
        .associate { (key, value) -> key to value.get() }
        .toSortedMap()
