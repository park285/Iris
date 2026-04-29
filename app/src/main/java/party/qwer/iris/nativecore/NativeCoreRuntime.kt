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

    private fun strictMode(component: NativeCoreComponent): Boolean = config.strictMode(component)

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
        if (!nativeUsable) {
            if (strictMode(NativeCoreComponent.DECRYPT)) {
                strictNativeUnavailable(NativeCoreComponent.DECRYPT)
            }
            recordNativeUnavailableFallback(NativeCoreComponent.DECRYPT, items.size)
            return kotlinDecryptBatch()
        }
        if (mode == NativeCoreMode.OFF) {
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
            eventTypeRoutes = emptyMap(),
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
        if (!nativeUsable) {
            if (strictMode(NativeCoreComponent.ROUTING)) {
                strictNativeUnavailable(NativeCoreComponent.ROUTING)
            }
            recordNativeUnavailableFallback(NativeCoreComponent.ROUTING, messages.size)
            return kotlinParseBatch()
        }
        if (mode == NativeCoreMode.OFF) {
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
                            eventTypeRoutes = emptyMap(),
                        )
                    }.onFailure { error -> recordNativeFailure(NativeCoreComponent.ROUTING, items.size, error = error) }
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
                        eventTypeRoutes = emptyMap(),
                    ).map { it.parsedCommand }
                }.getOrElse {
                    if (strictMode(NativeCoreComponent.ROUTING)) {
                        strictNativeFailure(NativeCoreComponent.ROUTING, items.size, error = it)
                    }
                    recordNativeFailure(NativeCoreComponent.ROUTING, items.size, error = it)
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
            eventTypeRoutes = emptyMap(),
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
        eventTypeRoutes: Map<String, List<String>>,
        kotlinResolve: () -> String?,
    ): String? =
        routingOrFallback(
            item = NativeRoutingBatchItem(text = "", messageType = messageType),
            commandRoutePrefixes = emptyMap(),
            imageMessageTypeRoutes = emptyMap(),
            eventTypeRoutes = eventTypeRoutes,
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
            eventTypeRoutes = emptyMap(),
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
        parseNoticesBatchOrFallback(listOf(meta)) {
            listOf(kotlinParse())
        }.single()

    fun parseNoticesBatchOrFallback(
        metas: List<String?>,
        kotlinParseBatch: () -> List<List<NoticeInfo>>,
    ): List<List<NoticeInfo>> =
        parserBatchOrFallback(
            items = metas.map { meta -> NativeParserBatchItem(kind = "notices", meta = meta) },
            kotlinParseBatch = kotlinParseBatch,
            nativeValue = { result -> result.toNoticeInfos() },
            detailKey = "notices",
        )

    fun parseIdArrayOrFallback(
        raw: String?,
        kotlinParse: () -> Set<Long>,
    ): Set<Long> =
        parseIdArraysOrFallback(listOf(raw)) {
            listOf(kotlinParse())
        }.single()

    fun parseIdArraysOrFallback(
        rawValues: List<String?>,
        kotlinParseBatch: () -> List<Set<Long>>,
    ): List<Set<Long>> =
        parserBatchOrFallback(
            items = rawValues.map { raw -> NativeParserBatchItem(kind = "idArray", raw = raw) },
            kotlinParseBatch = kotlinParseBatch,
            nativeValue = { result -> result.toIdSet() },
            detailKey = "idArray",
        )

    fun parseRoomInfoMetadataOrFallback(
        meta: String?,
        blindedMemberIds: String?,
        kotlinParse: () -> Pair<List<NoticeInfo>, Set<Long>>,
    ): Pair<List<NoticeInfo>, Set<Long>> {
        val parsed =
            parserBatchOrFallbackIndexed<Any>(
                items =
                    listOf(
                        NativeParserBatchItem(kind = "notices", meta = meta),
                        NativeParserBatchItem(kind = "idArray", raw = blindedMemberIds),
                    ),
                kotlinParseBatch = {
                    val kotlinResult = kotlinParse()
                    listOf(kotlinResult.first, kotlinResult.second)
                },
                nativeValue = { index, result ->
                    when (index) {
                        0 -> result.requireKind("notices").toNoticeInfos()
                        1 -> result.requireKind("idArray").toIdSet()
                        else -> error("native parsers failed")
                    }
                },
                detailKey = "roomInfo",
            )
        @Suppress("UNCHECKED_CAST")
        return (parsed[0] as List<NoticeInfo>) to (parsed[1] as Set<Long>)
    }

    fun parseLogMetadataBatchOrFallback(
        metadataValues: List<String>,
        kotlinParseBatch: () -> List<NativeLogMetadataProjection?>,
    ): List<NativeLogMetadataProjection?> =
        parserBatchOrFallback(
            items =
                metadataValues.map { rawMetadata ->
                    NativeParserBatchItem(kind = "logMetadata", metadata = rawMetadata)
                },
            kotlinParseBatch = kotlinParseBatch,
            nativeValue = { result -> result.toLogMetadataProjection() },
            detailKey = "logMetadata",
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
        if (!nativeUsable) {
            if (strictMode(NativeCoreComponent.WEBHOOK_PAYLOAD)) {
                strictNativeUnavailable(NativeCoreComponent.WEBHOOK_PAYLOAD)
            }
            recordNativeUnavailableFallback(NativeCoreComponent.WEBHOOK_PAYLOAD, 1)
            return kotlinBuild()
        }
        if (mode == NativeCoreMode.OFF) {
            return kotlinBuild()
        }
        return when (mode) {
            NativeCoreMode.OFF -> kotlinBuild()
            NativeCoreMode.SHADOW -> {
                val kotlinPayload = kotlinBuild()
                val nativePayload =
                    runCatching { callNativeWebhookPayload(command, route, messageId) }
                        .onFailure { error -> recordNativeFailure(NativeCoreComponent.WEBHOOK_PAYLOAD, 1, error = error) }
                        .getOrNull()
                if (nativePayload != null && !jsonEquivalent(kotlinPayload, nativePayload)) {
                    recordShadowMismatch(NativeCoreComponent.WEBHOOK_PAYLOAD, 1)
                }
                kotlinPayload
            }

            NativeCoreMode.ON ->
                runCatching { callNativeWebhookPayload(command, route, messageId) }
                    .getOrElse {
                        if (strictMode(NativeCoreComponent.WEBHOOK_PAYLOAD)) {
                            strictNativeFailure(NativeCoreComponent.WEBHOOK_PAYLOAD, 1, error = it)
                        }
                        recordNativeFailure(NativeCoreComponent.WEBHOOK_PAYLOAD, 1, error = it)
                        kotlinBuild()
                    }
        }
    }

    fun projectQueryRowsOrFallback(
        rows: List<List<NativeQueryProjectionCellEnvelope>>,
        kotlinProjectBatch: () -> List<List<NativeQueryProjectedCell>>,
    ): List<List<NativeQueryProjectedCell>> {
        if (rows.isEmpty()) return emptyList()
        val mode = config.effectiveMode(NativeCoreComponent.PROJECTIONS)
        val itemCount = queryProjectionItemCount(rows)
        if (!nativeUsable) {
            if (strictMode(NativeCoreComponent.PROJECTIONS)) {
                strictNativeUnavailable(NativeCoreComponent.PROJECTIONS, queryCellProjectionDetailKey)
            }
            recordNativeUnavailableFallback(NativeCoreComponent.PROJECTIONS, itemCount, queryCellProjectionDetailKey)
            return kotlinProjectBatch()
        }
        if (mode == NativeCoreMode.OFF) {
            return kotlinProjectBatch()
        }
        return when (mode) {
            NativeCoreMode.OFF -> kotlinProjectBatch()
            NativeCoreMode.SHADOW -> {
                val kotlinRows = kotlinProjectBatch()
                val nativeRows =
                    runCatching { callNativeQueryProjectionBatch(rows) }
                        .onFailure { error ->
                            recordNativeFailure(NativeCoreComponent.PROJECTIONS, itemCount, queryCellProjectionDetailKey, error)
                        }.getOrNull()
                if (nativeRows != null) {
                    recordShadowMismatch(
                        NativeCoreComponent.PROJECTIONS,
                        compareProjectedRows(kotlinRows, nativeRows),
                        queryCellProjectionDetailKey,
                    )
                }
                kotlinRows
            }

            NativeCoreMode.ON ->
                runCatching { callNativeQueryProjectionBatch(rows) }
                    .getOrElse {
                        if (strictMode(NativeCoreComponent.PROJECTIONS)) {
                            strictNativeFailure(NativeCoreComponent.PROJECTIONS, itemCount, queryCellProjectionDetailKey, it)
                        }
                        recordNativeFailure(NativeCoreComponent.PROJECTIONS, itemCount, queryCellProjectionDetailKey, it)
                        kotlinProjectBatch()
                    }
        }
    }

    fun projectRoomStatsOrFallback(
        item: NativeStatisticsProjectionBatchItem,
        kotlinProject: () -> NativeRoomStatsProjection,
    ): NativeRoomStatsProjection =
        projectStatisticsOrFallback(
            item = item,
            detailKey = roomStatsProjectionDetailKey,
            itemCount = statisticsProjectionItemCount(item),
            kotlinProject = kotlinProject,
            nativeProject = { result -> result.toRoomStatsProjection() },
        )

    fun projectMemberActivityOrFallback(
        item: NativeStatisticsProjectionBatchItem,
        kotlinProject: () -> NativeMemberActivityProjection,
    ): NativeMemberActivityProjection =
        projectStatisticsOrFallback(
            item = item,
            detailKey = memberActivityProjectionDetailKey,
            itemCount = statisticsProjectionItemCount(item),
            kotlinProject = kotlinProject,
            nativeProject = { result -> result.toMemberActivityProjection() },
        )

    private fun <T> projectStatisticsOrFallback(
        item: NativeStatisticsProjectionBatchItem,
        detailKey: String,
        itemCount: Int,
        kotlinProject: () -> T,
        nativeProject: (NativeStatisticsProjectionBatchResult) -> T,
    ): T {
        val mode = config.effectiveMode(NativeCoreComponent.PROJECTIONS)
        if (!nativeUsable) {
            if (strictMode(NativeCoreComponent.PROJECTIONS)) {
                strictNativeUnavailable(NativeCoreComponent.PROJECTIONS, detailKey)
            }
            recordNativeUnavailableFallback(NativeCoreComponent.PROJECTIONS, itemCount, detailKey)
            return kotlinProject()
        }
        if (mode == NativeCoreMode.OFF) {
            return kotlinProject()
        }
        return when (mode) {
            NativeCoreMode.OFF -> kotlinProject()
            NativeCoreMode.SHADOW -> {
                val kotlinResult = kotlinProject()
                val nativeResult =
                    runCatching { nativeProject(callNativeStatisticsProjectionBatch(listOf(item), itemCount).single()) }
                        .onFailure { error ->
                            recordNativeFailure(NativeCoreComponent.PROJECTIONS, itemCount, detailKey, error)
                        }.getOrNull()
                if (nativeResult != null && nativeResult != kotlinResult) {
                    recordShadowMismatch(NativeCoreComponent.PROJECTIONS, 1, detailKey)
                }
                kotlinResult
            }

            NativeCoreMode.ON ->
                runCatching { nativeProject(callNativeStatisticsProjectionBatch(listOf(item), itemCount).single()) }
                    .getOrElse {
                        if (strictMode(NativeCoreComponent.PROJECTIONS)) {
                            strictNativeFailure(NativeCoreComponent.PROJECTIONS, itemCount, detailKey, it)
                        }
                        recordNativeFailure(NativeCoreComponent.PROJECTIONS, itemCount, detailKey, it)
                        kotlinProject()
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
                .onFailure { error -> recordNativeFailure(NativeCoreComponent.DECRYPT, items.size, error = error) }
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
                if (strictMode(NativeCoreComponent.DECRYPT)) {
                    strictNativeFailure(NativeCoreComponent.DECRYPT, items.size, error = it)
                }
                recordNativeFailure(NativeCoreComponent.DECRYPT, items.size, error = it)
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
        eventTypeRoutes: Map<String, List<String>>,
        kotlinDecision: () -> NativeRoutingDecision,
        shadowEquivalent: (NativeRoutingDecision, NativeRoutingDecision) -> Boolean,
    ): NativeRoutingDecision {
        val mode = config.effectiveMode(NativeCoreComponent.ROUTING)
        if (!nativeUsable) {
            if (strictMode(NativeCoreComponent.ROUTING)) {
                strictNativeUnavailable(NativeCoreComponent.ROUTING)
            }
            recordNativeUnavailableFallback(NativeCoreComponent.ROUTING, 1)
            return kotlinDecision()
        }
        if (mode == NativeCoreMode.OFF) {
            return kotlinDecision()
        }
        return when (mode) {
            NativeCoreMode.OFF -> kotlinDecision()
            NativeCoreMode.SHADOW -> {
                val kotlinResult = kotlinDecision()
                val nativeResult =
                    runCatching { callNativeRouting(item, commandRoutePrefixes, imageMessageTypeRoutes, eventTypeRoutes) }
                        .onFailure { error -> recordNativeFailure(NativeCoreComponent.ROUTING, 1, error = error) }
                        .getOrNull()
                if (nativeResult != null && !shadowEquivalent(kotlinResult, nativeResult)) {
                    recordShadowMismatch(NativeCoreComponent.ROUTING, 1)
                }
                kotlinResult
            }

            NativeCoreMode.ON ->
                runCatching { callNativeRouting(item, commandRoutePrefixes, imageMessageTypeRoutes, eventTypeRoutes) }
                    .getOrElse {
                        if (strictMode(NativeCoreComponent.ROUTING)) {
                            strictNativeFailure(NativeCoreComponent.ROUTING, 1, error = it)
                        }
                        recordNativeFailure(NativeCoreComponent.ROUTING, 1, error = it)
                        kotlinDecision()
                    }
        }
    }

    private fun callNativeRouting(
        item: NativeRoutingBatchItem,
        commandRoutePrefixes: Map<String, List<String>>,
        imageMessageTypeRoutes: Map<String, List<String>>,
        eventTypeRoutes: Map<String, List<String>>,
    ): NativeRoutingDecision =
        callNativeRoutingBatch(
            items = listOf(item),
            commandRoutePrefixes = commandRoutePrefixes,
            imageMessageTypeRoutes = imageMessageTypeRoutes,
            eventTypeRoutes = eventTypeRoutes,
        ).singleOrNull() ?: error("native routing failed")

    private fun callNativeRoutingBatch(
        items: List<NativeRoutingBatchItem>,
        commandRoutePrefixes: Map<String, List<String>>,
        imageMessageTypeRoutes: Map<String, List<String>>,
        eventTypeRoutes: Map<String, List<String>>,
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
                    eventTypeRoutes = eventTypeRoutes.toNativeRouteEntries(),
                )
            val rawResponse = jni.routingBatch(json.encodeToString(request).encodeToByteArray()).decodeToString()
            val response = json.decodeFromString<NativeRoutingBatchResponse>(rawResponse)
            if (response.items.size != items.size) {
                val first = response.items.singleOrNull()
                throw NativeCoreDiagnosticFailure(
                    batchEnvelopeFailureReason(items.size, response.items.size, first?.ok, first?.errorKind),
                    "native routing failed",
                )
            }
            response.items.map { result ->
                if (!result.ok) {
                    throw NativeCoreDiagnosticFailure(nativeErrorReason(result.errorKind), "native routing failed")
                }
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
        if (!nativeUsable) {
            if (strictMode(NativeCoreComponent.PARSERS)) {
                strictNativeUnavailable(NativeCoreComponent.PARSERS, item.kind)
            }
            recordNativeUnavailableFallback(NativeCoreComponent.PARSERS, 1, item.kind)
            return kotlinParse()
        }
        if (mode == NativeCoreMode.OFF) {
            return kotlinParse()
        }
        return when (mode) {
            NativeCoreMode.OFF -> kotlinParse()
            NativeCoreMode.SHADOW -> {
                val kotlinResult = kotlinParse()
                val nativeResult =
                    runCatching { callNativeParser(item) }
                        .onFailure { error -> recordNativeFailure(NativeCoreComponent.PARSERS, 1, item.kind, error) }
                        .getOrNull()
                if (nativeResult != null) {
                    if (nativeResult.usedDefault) {
                        recordParserDefaultUse(1, item.kind)
                    }
                    if (nativeResult.fallback) {
                        recordComponentFallback(NativeCoreComponent.PARSERS, 1, item.kind, fallbackRequiredReason)
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
                            if (strictMode(NativeCoreComponent.PARSERS)) {
                                strictNativeFailure(NativeCoreComponent.PARSERS, 1, item.kind, it)
                            }
                            recordNativeFailure(NativeCoreComponent.PARSERS, 1, item.kind, it)
                            return kotlinParse()
                        }
                if (nativeResult.usedDefault) {
                    recordParserDefaultUse(1, item.kind)
                }
                if (nativeResult.fallback) {
                    if (strictMode(NativeCoreComponent.PARSERS)) {
                        strictNativeFailure(
                            component = NativeCoreComponent.PARSERS,
                            fallbackItems = 1,
                            detailKey = item.kind,
                            reasonKey = fallbackRequiredReason,
                        )
                    } else {
                        recordComponentFallback(NativeCoreComponent.PARSERS, 1, item.kind, fallbackRequiredReason)
                    }
                    kotlinParse()
                } else {
                    runCatching { nativeValue(nativeResult) }
                        .getOrElse {
                            if (strictMode(NativeCoreComponent.PARSERS)) {
                                strictNativeFailure(
                                    component = NativeCoreComponent.PARSERS,
                                    fallbackItems = 1,
                                    detailKey = item.kind,
                                    error = it,
                                    reasonKey = schemaDecodeErrorReason,
                                )
                            }
                            recordNativeFailure(NativeCoreComponent.PARSERS, 1, item.kind, reasonKey = schemaDecodeErrorReason)
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
        eventTypeRoutes: Map<String, List<String>>,
        kotlinPlanBatch: () -> List<NativeIngressPlan>,
    ): List<NativeIngressPlan> {
        if (commands.isEmpty()) return emptyList()
        val routingMode = config.effectiveMode(NativeCoreComponent.ROUTING)
        val payloadMode = config.effectiveMode(NativeCoreComponent.WEBHOOK_PAYLOAD)
        if (!nativeUsable) {
            if (strictMode(NativeCoreComponent.ROUTING) || strictMode(NativeCoreComponent.WEBHOOK_PAYLOAD)) {
                strictNativeIngressFailure(
                    fallbackItems = commands.size,
                    error = null,
                    reasonKey = nativeUnavailableReason,
                )
            }
            if (routingMode != NativeCoreMode.OFF) {
                recordNativeUnavailableFallback(NativeCoreComponent.ROUTING, commands.size)
            }
            if (payloadMode != NativeCoreMode.OFF) {
                recordNativeUnavailableFallback(NativeCoreComponent.WEBHOOK_PAYLOAD, commands.size)
            }
            return kotlinPlanBatch()
        }
        if (routingMode == NativeCoreMode.OFF && payloadMode == NativeCoreMode.OFF) {
            return kotlinPlanBatch()
        }

        if (routingMode == NativeCoreMode.ON && payloadMode == NativeCoreMode.ON) {
            return runCatching { callNativeIngressBatch(commands, commandRoutePrefixes, imageMessageTypeRoutes, eventTypeRoutes) }
                .getOrElse {
                    if (strictMode(NativeCoreComponent.ROUTING) || strictMode(NativeCoreComponent.WEBHOOK_PAYLOAD)) {
                        strictNativeIngressFailure(commands.size, it)
                    }
                    recordNativeIngressFailure(commands.size, it)
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
                    callNativeRoutingBatch(items, commandRoutePrefixes, imageMessageTypeRoutes, eventTypeRoutes)
                        .toIngressPlans(commands)
                }.getOrElse { error ->
                    if (strictMode(NativeCoreComponent.ROUTING)) {
                        strictNativeFailure(NativeCoreComponent.ROUTING, commands.size, error = error)
                    }
                    recordNativeFailure(NativeCoreComponent.ROUTING, commands.size, error = error)
                    null
                }
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
    ): List<T> =
        parserBatchOrFallbackIndexed(
            items = items,
            kotlinParseBatch = kotlinParseBatch,
            nativeValue = { _, result -> nativeValue(result) },
            detailKey = detailKey,
        )

    private fun <T> parserBatchOrFallbackIndexed(
        items: List<NativeParserBatchItem>,
        kotlinParseBatch: () -> List<T>,
        nativeValue: (Int, NativeParserBatchResult) -> T,
        detailKey: String,
    ): List<T> {
        if (items.isEmpty()) return emptyList()
        val mode = config.effectiveMode(NativeCoreComponent.PARSERS)
        if (!nativeUsable) {
            if (strictMode(NativeCoreComponent.PARSERS)) {
                strictNativeUnavailable(NativeCoreComponent.PARSERS, detailKey)
            }
            recordNativeUnavailableFallback(NativeCoreComponent.PARSERS, items.size, detailKey)
            return kotlinParseBatch()
        }
        if (mode == NativeCoreMode.OFF) {
            return kotlinParseBatch()
        }
        return when (mode) {
            NativeCoreMode.OFF -> kotlinParseBatch()
            NativeCoreMode.SHADOW -> {
                val kotlinResults = kotlinParseBatch()
                val nativeResults =
                    runCatching { callNativeParserBatch(items) }
                        .onFailure { error -> recordNativeFailure(NativeCoreComponent.PARSERS, items.size, detailKey, error) }
                        .getOrNull()
                if (nativeResults != null) {
                    val usedDefaultCount = nativeResults.count { result -> result.usedDefault }
                    recordParserDefaultUse(usedDefaultCount, detailKey)
                    val fallbackCount = nativeResults.count { result -> result.fallback }
                    recordComponentFallback(NativeCoreComponent.PARSERS, fallbackCount, detailKey, fallbackRequiredReason)
                    val nativeConverted = nativeResults.mapIndexed { index, result -> runCatching { nativeValue(index, result) }.getOrNull() }
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
                            if (strictMode(NativeCoreComponent.PARSERS)) {
                                strictNativeFailure(NativeCoreComponent.PARSERS, items.size, detailKey, it)
                            }
                            recordNativeFailure(NativeCoreComponent.PARSERS, items.size, detailKey, it)
                            return kotlinParseBatch()
                        }
                val usedDefaultCount = nativeResults.count { result -> result.usedDefault }
                recordParserDefaultUse(usedDefaultCount, detailKey)
                val fallbackCount = nativeResults.count { result -> result.fallback }
                if (fallbackCount > 0) {
                    if (strictMode(NativeCoreComponent.PARSERS)) {
                        strictNativeFailure(
                            component = NativeCoreComponent.PARSERS,
                            fallbackItems = items.size,
                            detailKey = detailKey,
                            reasonKey = fallbackRequiredReason,
                        )
                    } else {
                        recordComponentFallback(NativeCoreComponent.PARSERS, items.size, detailKey, fallbackRequiredReason)
                    }
                    return kotlinParseBatch()
                }
                runCatching { nativeResults.mapIndexed(nativeValue) }
                    .getOrElse {
                        if (strictMode(NativeCoreComponent.PARSERS)) {
                            strictNativeFailure(
                                component = NativeCoreComponent.PARSERS,
                                fallbackItems = items.size,
                                detailKey = detailKey,
                                error = it,
                                reasonKey = schemaDecodeErrorReason,
                            )
                        }
                        recordNativeFailure(NativeCoreComponent.PARSERS, items.size, detailKey, reasonKey = schemaDecodeErrorReason)
                        kotlinParseBatch()
                    }
            }
        }
    }

    private fun NativeParserBatchResult.toNoticeInfos(): List<NoticeInfo> =
        notices.map { notice ->
            NoticeInfo(
                content = notice.content,
                authorId = notice.authorId,
                updatedAt = notice.updatedAt,
            )
        }

    private fun NativeParserBatchResult.toIdSet(): Set<Long> = ids.toSet()

    private fun NativeParserBatchResult.toLogMetadataProjection(): NativeLogMetadataProjection {
        val result = requireKind("logMetadata")
        return NativeLogMetadataProjection(
            enc = result.enc ?: throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, "native parsers failed"),
            origin = result.origin ?: throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, "native parsers failed"),
        )
    }

    private fun NativeParserBatchResult.requireKind(expected: String): NativeParserBatchResult {
        if (kind.isNotBlank() && kind != expected) {
            throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, "native parsers failed")
        }
        return this
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
                val first = response.items.singleOrNull()
                throw NativeCoreDiagnosticFailure(
                    batchEnvelopeFailureReason(items.size, response.items.size, first?.ok, first?.errorKind),
                    "native parsers failed",
                )
            }
            response.items.map { result ->
                if (!result.ok) {
                    throw NativeCoreDiagnosticFailure(nativeErrorReason(result.errorKind), "native parsers failed")
                }
                result
            }
        } finally {
            stats.recordNativeDuration(System.nanoTime() - startedAt)
        }
    }

    private fun callNativeQueryProjectionBatch(
        rows: List<List<NativeQueryProjectionCellEnvelope>>,
    ): List<List<NativeQueryProjectedCell>> {
        if (rows.isEmpty()) return emptyList()
        val stats = statsFor(NativeCoreComponent.PROJECTIONS)
        val itemCount = queryProjectionItemCount(rows)
        stats.jniCalls.incrementAndGet()
        stats.items.addAndGet(itemCount.toLong())
        val startedAt = System.nanoTime()
        return try {
            val request = NativeQueryProjectionBatchRequest(rows.map { row -> NativeQueryProjectionBatchItem(cells = row) })
            val rawResponse = jni.queryProjectionBatch(json.encodeToString(request).encodeToByteArray()).decodeToString()
            val response = json.decodeFromString<NativeQueryProjectionBatchResponse>(rawResponse)
            if (response.items.size != rows.size) {
                val first = response.items.singleOrNull()
                throw NativeCoreDiagnosticFailure(
                    batchEnvelopeFailureReason(rows.size, response.items.size, first?.ok, first?.errorKind),
                    "native projections failed",
                )
            }
            response.items.mapIndexed { index, result ->
                if (!result.ok) {
                    throw NativeCoreDiagnosticFailure(nativeErrorReason(result.errorKind), "native projections failed")
                }
                val cells =
                    result.cells ?: throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, "native projections failed")
                if (cells.size != rows[index].size) {
                    throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, "native projections failed")
                }
                cells
            }
        } finally {
            stats.recordNativeDuration(System.nanoTime() - startedAt)
        }
    }

    private fun callNativeStatisticsProjectionBatch(
        items: List<NativeStatisticsProjectionBatchItem>,
        itemCount: Int,
    ): List<NativeStatisticsProjectionBatchResult> {
        if (items.isEmpty()) return emptyList()
        val stats = statsFor(NativeCoreComponent.PROJECTIONS)
        stats.jniCalls.incrementAndGet()
        stats.items.addAndGet(itemCount.toLong())
        val startedAt = System.nanoTime()
        return try {
            val request = NativeStatisticsProjectionBatchRequest(items = items)
            val rawResponse = jni.statisticsProjectionBatch(json.encodeToString(request).encodeToByteArray()).decodeToString()
            val response = json.decodeFromString<NativeStatisticsProjectionBatchResponse>(rawResponse)
            if (response.items.size != items.size) {
                val first = response.items.singleOrNull()
                throw NativeCoreDiagnosticFailure(
                    batchEnvelopeFailureReason(items.size, response.items.size, first?.ok, first?.errorKind),
                    "native projections failed",
                )
            }
            response.items.mapIndexed { index, result ->
                if (!result.ok) {
                    throw NativeCoreDiagnosticFailure(nativeErrorReason(result.errorKind), "native projections failed")
                }
                if (result.kind != items[index].kind) {
                    throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, "native projections failed")
                }
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
                val first = response.items.singleOrNull()
                throw NativeCoreDiagnosticFailure(
                    batchEnvelopeFailureReason(items.size, response.items.size, first?.ok, first?.errorKind),
                    "native webhook payload failed",
                )
            }
            response.items.map { result ->
                if (!result.ok) {
                    throw NativeCoreDiagnosticFailure(nativeErrorReason(result.errorKind), "native webhook payload failed")
                }
                result.payloadJson ?: throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, "native webhook payload failed")
            }
        } finally {
            stats.recordNativeDuration(System.nanoTime() - startedAt)
        }
    }

    private fun callNativeIngressBatch(
        commands: List<RoutingCommand>,
        commandRoutePrefixes: Map<String, List<String>>,
        imageMessageTypeRoutes: Map<String, List<String>>,
        eventTypeRoutes: Map<String, List<String>>,
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
                    eventTypeRoutes = eventTypeRoutes.toNativeRouteEntries(),
                )
            val rawResponse = jni.ingressBatch(json.encodeToString(request).encodeToByteArray()).decodeToString()
            val response = json.decodeFromString<NativeIngressBatchResponse>(rawResponse)
            if (response.items.size != commands.size) {
                val first = response.items.singleOrNull()
                throw NativeCoreDiagnosticFailure(
                    batchEnvelopeFailureReason(commands.size, response.items.size, first?.ok, first?.errorKind),
                    "native ingress failed",
                )
            }
            response.items
                .map { result ->
                    if (!result.ok) {
                        throw NativeCoreDiagnosticFailure(nativeErrorReason(result.errorKind), "native ingress failed")
                    }
                    val targetRoute = result.targetRoute
                    if (targetRoute != null && (result.messageId.isNullOrBlank() || result.payloadJson == null)) {
                        throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, "native ingress failed")
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
        reasonKey: String = nativeFallbackReason,
    ) {
        statsFor(component).recordFallback(fallbackItems, detailKey, reasonKey)
    }

    private fun recordParserDefaultUse(
        count: Int,
        detailKey: String?,
    ) {
        statsFor(NativeCoreComponent.PARSERS).recordParserDefaultUse(count, detailKey)
    }

    private fun recordNativeUnavailableFallback(
        component: NativeCoreComponent,
        fallbackItems: Int,
        detailKey: String? = null,
    ) {
        val mode = config.effectiveMode(component)
        if (mode == NativeCoreMode.OFF) return
        statsFor(component).recordFallback(fallbackItems, detailKey, nativeUnavailableReason)
    }

    private fun strictNativeUnavailable(
        component: NativeCoreComponent,
        detailKey: String? = null,
    ): Nothing =
        strictNativeFailure(
            component = component,
            fallbackItems = 0,
            detailKey = detailKey,
            reasonKey = nativeUnavailableReason,
        )

    private fun strictNativeFailure(
        component: NativeCoreComponent,
        fallbackItems: Int,
        detailKey: String? = null,
        error: Throwable? = null,
        reasonKey: String? = null,
    ): Nothing {
        val message = nativeFailureMessage(component)
        val reason = reasonKey ?: classifyNativeFailure(error)
        recordStrictFailure(component, fallbackItems, detailKey, reason, message)
        throw NativeCoreDiagnosticFailure(reason, message)
    }

    private fun strictNativeIngressFailure(
        fallbackItems: Int,
        error: Throwable?,
        reasonKey: String? = null,
    ): Nothing {
        val message = "native ingress failed"
        val reason = reasonKey ?: classifyNativeFailure(error)
        val strictComponents =
            listOf(NativeCoreComponent.ROUTING, NativeCoreComponent.WEBHOOK_PAYLOAD)
                .filter { component -> strictMode(component) }
        callFailures.incrementAndGet()
        strictComponents.forEach { component ->
            val stats = statsFor(component)
            stats.recordFailure(reason)
            stats.lastError.set(message)
        }
        lastErrorRef.set(message)
        IrisLogger.error("[NativeCore] strict native call failed: $message reason=$reason items=$fallbackItems")
        throw NativeCoreDiagnosticFailure(reason, message)
    }

    private fun recordStrictFailure(
        component: NativeCoreComponent,
        items: Int,
        detailKey: String?,
        reason: String,
        message: String,
    ) {
        callFailures.incrementAndGet()
        val stats = statsFor(component)
        stats.recordFailure(reason)
        stats.lastError.set(message)
        lastErrorRef.set(message)
        IrisLogger.error("[NativeCore] strict native call failed: $message reason=$reason items=$items detail=${detailKey.orEmpty()}")
    }

    private fun recordNativeFailure(
        component: NativeCoreComponent,
        fallbackItems: Int,
        detailKey: String? = null,
        error: Throwable? = null,
        reasonKey: String? = null,
    ) {
        val message = nativeFailureMessage(component)
        val reason = reasonKey ?: classifyNativeFailure(error)
        val stats = statsFor(component)
        callFailures.incrementAndGet()
        stats.recordFallback(fallbackItems, detailKey, reason)
        stats.recordFailure(reason)
        stats.lastError.set(message)
        lastErrorRef.set(message)
        IrisLogger.error("[NativeCore] native call failed: $message reason=$reason")
    }

    private fun nativeFailureMessage(component: NativeCoreComponent): String =
        when (component) {
            NativeCoreComponent.DECRYPT -> nativeDecryptFailure
            NativeCoreComponent.ROUTING -> "native routing failed"
            NativeCoreComponent.PARSERS -> "native parsers failed"
            NativeCoreComponent.PROJECTIONS -> "native projections failed"
            NativeCoreComponent.WEBHOOK_PAYLOAD -> "native webhook payload failed"
        }

    private fun recordNativeIngressFailure(
        fallbackItems: Int,
        error: Throwable?,
    ) {
        val message = "native ingress failed"
        val reason = classifyNativeFailure(error)
        callFailures.incrementAndGet()
        listOf(NativeCoreComponent.ROUTING, NativeCoreComponent.WEBHOOK_PAYLOAD).forEach { component ->
            val stats = statsFor(component)
            stats.recordFallback(fallbackItems, null, reason)
            stats.recordFailure(reason)
            stats.lastError.set(message)
        }
        lastErrorRef.set(message)
        IrisLogger.error("[NativeCore] native call failed: $message reason=$reason")
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
                val first = response.items.singleOrNull()
                throw NativeCoreDiagnosticFailure(
                    batchEnvelopeFailureReason(items.size, response.items.size, first?.ok, first?.errorKind, ::decryptItemErrorReason),
                    nativeDecryptFailure,
                )
            }
            response.items.map { item ->
                if (!item.ok) throw NativeCoreDiagnosticFailure(decryptItemErrorReason(item.errorKind), nativeDecryptFailure)
                item.plaintext ?: throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, nativeDecryptFailure)
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
                .onFailure { error -> recordNativeFailure(NativeCoreComponent.WEBHOOK_PAYLOAD, inputs.size, error = error) }
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
            if (strictMode(NativeCoreComponent.WEBHOOK_PAYLOAD)) {
                strictNativeFailure(NativeCoreComponent.WEBHOOK_PAYLOAD, inputs.size, error = it)
            }
            recordNativeFailure(NativeCoreComponent.WEBHOOK_PAYLOAD, inputs.size, error = it)
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

    private fun compareProjectedRows(
        expected: List<List<NativeQueryProjectedCell>>,
        actual: List<List<NativeQueryProjectedCell>>,
    ): Int {
        if (expected.size != actual.size) return maxOf(expected.size, actual.size)
        return expected.zip(actual).sumOf { (expectedRow, actualRow) ->
            compareByIndex(expectedRow, actualRow) { expectedCell, actualCell -> expectedCell == actualCell }
        }
    }

    private fun queryProjectionItemCount(rows: List<List<NativeQueryProjectionCellEnvelope>>): Int = rows.sumOf { row -> row.size }.coerceAtLeast(rows.size)

    private fun statisticsProjectionItemCount(item: NativeStatisticsProjectionBatchItem): Int = item.rows.size.coerceAtLeast(1)

    private fun NativeStatisticsProjectionBatchResult.toRoomStatsProjection(): NativeRoomStatsProjection =
        NativeRoomStatsProjection(
            memberStats = memberStats ?: throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, "native projections failed"),
            totalMessages = totalMessages ?: throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, "native projections failed"),
            activeMembers = activeMembers ?: throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, "native projections failed"),
        )

    private fun NativeStatisticsProjectionBatchResult.toMemberActivityProjection(): NativeMemberActivityProjection {
        val activeHours = activeHours ?: throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, "native projections failed")
        if (activeHours.size != 24) {
            throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, "native projections failed")
        }
        return NativeMemberActivityProjection(
            messageCount = messageCount ?: throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, "native projections failed"),
            firstMessageAt = firstMessageAt,
            lastMessageAt = lastMessageAt,
            activeHours = activeHours,
            messageTypes = messageTypes ?: throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, "native projections failed"),
        )
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
        private val fallbackReasons = ConcurrentHashMap<String, AtomicLong>()
        private val failureReasons = ConcurrentHashMap<String, AtomicLong>()
        private val shadowMismatchesByKey = ConcurrentHashMap<String, AtomicLong>()
        private val parserDefaultUses = AtomicLong(0)
        private val parserDefaultUsesByKey = ConcurrentHashMap<String, AtomicLong>()

        fun recordFallback(
            count: Int,
            detailKey: String?,
            reasonKey: String,
        ) {
            if (count <= 0) return
            fallbacks.addAndGet(count.toLong())
            fallbackReasons.computeIfAbsent(reasonKey) { AtomicLong(0) }.addAndGet(count.toLong())
            detailKey?.let { key ->
                fallbacksByKey.computeIfAbsent(key) { AtomicLong(0) }.addAndGet(count.toLong())
            }
        }

        fun recordFailure(reasonKey: String) {
            failureReasons.computeIfAbsent(reasonKey) { AtomicLong(0) }.incrementAndGet()
        }

        fun recordParserDefaultUse(
            count: Int,
            detailKey: String?,
        ) {
            if (count <= 0) return
            parserDefaultUses.addAndGet(count.toLong())
            detailKey?.let { key ->
                parserDefaultUsesByKey.computeIfAbsent(key) { AtomicLong(0) }.addAndGet(count.toLong())
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
            val itemCount = items.get()
            val totalMicros = totalNativeNanos.get().nanosToMicros()
            return NativeCoreComponentDiagnostics(
                mode = mode.name.lowercase(),
                jniCalls = callCount,
                items = itemCount,
                fallbacks = fallbacks.get(),
                shadowMismatches = shadowMismatches.get(),
                totalNativeMicros = totalMicros,
                maxNativeMicros = maxNativeNanos.get().nanosToMicros(),
                averageNativeMicros = if (callCount > 0L) totalMicros / callCount else 0L,
                averageItemNativeMicros = if (itemCount > 0L) totalMicros / itemCount else 0L,
                fallbacksByKey = fallbacksByKey.snapshotCounts(),
                fallbackReasons = fallbackReasons.snapshotCounts(),
                failureReasons = failureReasons.snapshotCounts(),
                shadowMismatchesByKey = shadowMismatchesByKey.snapshotCounts(),
                parserDefaultUses = parserDefaultUses.get(),
                parserDefaultUsesByKey = parserDefaultUsesByKey.snapshotCounts(),
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

private class NativeCoreDiagnosticFailure(
    val reasonKey: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private const val jniErrorReason = "jniError"
private const val schemaDecodeErrorReason = "schemaDecodeError"
private const val responseSizeMismatchReason = "responseSizeMismatch"
private const val itemErrorReason = "itemError"
private const val nativeUnavailableReason = "nativeUnavailable"
private const val nativeFallbackReason = "nativeFallback"
private const val fallbackRequiredReason = "fallbackRequired"
private const val queryCellProjectionDetailKey = "queryCell"
private const val roomStatsProjectionDetailKey = "roomStats"
private const val memberActivityProjectionDetailKey = "memberActivity"

private fun classifyNativeFailure(error: Throwable?): String =
    when (error) {
        is NativeCoreDiagnosticFailure -> error.reasonKey
        is kotlinx.serialization.SerializationException -> schemaDecodeErrorReason
        else -> jniErrorReason
    }

private fun batchEnvelopeFailureReason(
    expectedSize: Int,
    actualSize: Int,
    singleOk: Boolean?,
    singleErrorKind: String?,
    errorKindMapper: (String?) -> String = ::nativeErrorReason,
): String =
    if (expectedSize > 1 && actualSize == 1 && singleOk == false && singleErrorKind != null) {
        errorKindMapper(singleErrorKind)
    } else {
        responseSizeMismatchReason
    }

private fun nativeErrorReason(errorKind: String?): String =
    when (errorKind?.trim()?.lowercase()) {
        "invalidrequest", "invalid_request" -> "invalidRequest"
        "invalidresponse", "invalid_response" -> "invalidResponse"
        "jnierror", "jni_error" -> jniErrorReason
        "panic" -> "panic"
        "decrypt" -> "decrypt"
        else -> itemErrorReason
    }

private fun decryptItemErrorReason(errorKind: String?): String =
    when (errorKind?.trim()?.lowercase()) {
        "invalidrequest", "invalid_request" -> "invalidRequest"
        "invalidresponse", "invalid_response" -> "invalidResponse"
        "jnierror", "jni_error" -> jniErrorReason
        "panic" -> "panic"
        "invalidbase64", "invalid_base64", "base64" -> "itemError.invalidBase64"
        "invalidpadding", "invalid_padding", "badpadding", "bad_padding" -> "itemError.invalidPadding"
        "invalidutf8", "invalid_utf8", "utf8" -> "itemError.invalidUtf8"
        "unsupportedenctype", "unsupported_enc_type", "unsupported" -> "itemError.unsupportedEncType"
        "decrypt", "decrypterror", "decrypt_error", "cryptoerror", "crypto_error" -> "itemError.decryptError"
        else -> itemErrorReason
    }

private fun NativeRoutingBatchResult.toParsedCommand(): ParsedCommand =
    ParsedCommand(
        kind =
            when (kind) {
                "NONE" -> CommandKind.NONE
                "COMMENT" -> CommandKind.COMMENT
                "WEBHOOK" -> CommandKind.WEBHOOK
                else -> throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, "native routing failed")
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
                else -> throw NativeCoreDiagnosticFailure(schemaDecodeErrorReason, "native ingress failed")
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
