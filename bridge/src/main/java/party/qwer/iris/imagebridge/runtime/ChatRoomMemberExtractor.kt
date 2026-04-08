package party.qwer.iris.imagebridge.runtime

import android.util.Log
import party.qwer.iris.ImageBridgeProtocol
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.lang.reflect.Array as ReflectArray

internal class ChatRoomMemberExtractor(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val fieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    fun snapshot(
        roomId: Long,
        room: Any,
        expectedMemberHints: Collection<ImageBridgeProtocol.ChatRoomMemberHint> = emptyList(),
        preferredPlan: ImageBridgeProtocol.ChatRoomMemberExtractionPlan? = null,
    ): ImageBridgeProtocol.ChatRoomMembersSnapshot {
        val expectedMemberIds = expectedMemberHints.map { it.userId }.toSet()
        check(expectedMemberIds.isNotEmpty()) { "expected member ids required" }
        val expectedNicknames =
            expectedMemberHints
                .mapNotNull { hint ->
                    hint.nickname
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let { nickname -> hint.userId to nickname }
                }.toMap(linkedMapOf())
        val containers = collectContainers(room)

        preferredPlan
            ?.let(::toInternalPlan)
            ?.let { plan ->
                applyPreferredPlan(
                    roomId = roomId,
                    containers = containers,
                    expectedMemberIds = expectedMemberIds,
                    expectedNicknames = expectedNicknames,
                    preferredPlan = plan,
                )
            }?.let { return it }

        return discoverBestSnapshot(
            roomId = roomId,
            containers = containers,
            expectedMemberIds = expectedMemberIds,
            expectedNicknames = expectedNicknames,
        )
    }

    fun snapshot(
        roomId: Long,
        room: Any,
        expectedMemberIds: Set<Long>,
    ): ImageBridgeProtocol.ChatRoomMembersSnapshot =
        snapshot(
            roomId = roomId,
            room = room,
            expectedMemberHints = expectedMemberIds.map { userId -> ImageBridgeProtocol.ChatRoomMemberHint(userId = userId) },
        )

    private fun applyPreferredPlan(
        roomId: Long,
        containers: List<ContainerCandidate>,
        expectedMemberIds: Set<Long>,
        expectedNicknames: Map<Long, String>,
        preferredPlan: ExtractionPlan,
    ): ImageBridgeProtocol.ChatRoomMembersSnapshot? {
        val container = containers.firstOrNull { candidate -> candidate.path == preferredPlan.containerPath } ?: return null
        val views = container.views().filter { it.values.isNotEmpty() }
        if (views.isEmpty()) {
            return null
        }
        val sourceClassName = views.firstOrNull { it.className != "<null>" }?.className
        if (!preferredPlan.sourceClassName.isNullOrBlank() && preferredPlan.sourceClassName != sourceClassName) {
            return null
        }

        val members =
            buildMembers(
                views = views,
                userPath = preferredPlan.userIdPath,
                nicknamePath = preferredPlan.nicknamePath,
                rolePath = preferredPlan.rolePath,
                profilePath = preferredPlan.profileImagePath,
            ).filter { member -> member.userId in expectedMemberIds }
        if (members.isEmpty()) {
            return null
        }

        val matchedExpected = expectedMemberIds.count { expectedId -> members.any { member -> member.userId == expectedId } }
        if (matchedExpected == 0) {
            return null
        }
        val expectedNicknameMatches =
            members.count { member ->
                expectedNicknames[member.userId] == member.nickname
            }
        return membersSnapshot(
            roomId = roomId,
            candidate =
                RankedContainerCandidate(
                    plan = preferredPlan,
                    members = members,
                    score = 10_000 + matchedExpected * 400 + expectedNicknameMatches * 600,
                    expectedNicknameMatches = expectedNicknameMatches,
                    matchedExpectedCount = matchedExpected,
                    hasRolePath = preferredPlan.rolePath != null,
                    hasProfilePath = preferredPlan.profileImagePath != null,
                    sourceClassName = sourceClassName,
                    containerType = container.typeLabel(),
                    genericLabelPenalty = 0,
                ),
            confidence = ImageBridgeProtocol.ChatRoomSnapshotConfidence.HIGH,
            confidenceScore = 10_000 + matchedExpected * 400 + expectedNicknameMatches * 600,
            usedPreferredPlan = true,
            candidateGap = null,
        )
    }

    private fun discoverBestSnapshot(
        roomId: Long,
        containers: List<ContainerCandidate>,
        expectedMemberIds: Set<Long>,
        expectedNicknames: Map<Long, String>,
    ): ImageBridgeProtocol.ChatRoomMembersSnapshot {
        val ranked =
            containers
                .mapNotNull { evaluateContainer(it, expectedMemberIds, expectedNicknames) }
                .sortedByDescending { it.score }
        val best =
            ranked.firstOrNull()
                ?: run {
                    logMissingCandidateDiagnostics(roomId, containers, expectedMemberIds, expectedNicknames)
                    error("chatroom member candidates not found")
                }
        val second = ranked.getOrNull(1)
        val candidateGap = second?.let { candidate -> best.score - candidate.score }
        val confidence = confidenceFor(best, candidateGap)
        return membersSnapshot(
            roomId = roomId,
            candidate = best,
            confidence = confidence,
            confidenceScore = best.score,
            usedPreferredPlan = false,
            candidateGap = candidateGap,
        )
    }

    private fun membersSnapshot(
        roomId: Long,
        candidate: RankedContainerCandidate,
        confidence: ImageBridgeProtocol.ChatRoomSnapshotConfidence,
        confidenceScore: Int,
        usedPreferredPlan: Boolean,
        candidateGap: Int?,
    ): ImageBridgeProtocol.ChatRoomMembersSnapshot =
        ImageBridgeProtocol.ChatRoomMembersSnapshot(
            roomId = roomId,
            sourcePath = candidate.plan.containerPath,
            sourceClassName = candidate.sourceClassName,
            scannedAtEpochMs = clock(),
            members = candidate.members.sortedBy { it.userId },
            selectedPlan = candidate.plan.toProtocolPlan(),
            confidence = confidence,
            confidenceScore = confidenceScore,
            usedPreferredPlan = usedPreferredPlan,
            candidateGap = candidateGap,
        )

    private fun confidenceFor(
        candidate: RankedContainerCandidate,
        candidateGap: Int?,
    ): ImageBridgeProtocol.ChatRoomSnapshotConfidence =
        when {
            candidate.expectedNicknameMatches >= 2 && (candidateGap ?: 0) >= 180 -> ImageBridgeProtocol.ChatRoomSnapshotConfidence.HIGH
            candidate.expectedNicknameMatches >= 1 &&
                candidate.containerType == CONTAINER_TYPE_COLLECTION &&
                candidate.genericLabelPenalty < 100 &&
                (candidateGap ?: 0) >= 160 -> ImageBridgeProtocol.ChatRoomSnapshotConfidence.HIGH

            candidate.matchedExpectedCount >= 2 &&
                candidate.containerType == CONTAINER_TYPE_COLLECTION &&
                ((candidateGap ?: 0) >= 120 || candidate.hasRolePath || candidate.hasProfilePath) -> ImageBridgeProtocol.ChatRoomSnapshotConfidence.MEDIUM

            candidate.matchedExpectedCount >= 1 &&
                candidate.containerType == CONTAINER_TYPE_COLLECTION &&
                candidate.hasRolePath &&
                candidate.hasProfilePath -> ImageBridgeProtocol.ChatRoomSnapshotConfidence.MEDIUM

            candidate.matchedExpectedCount >= 2 &&
                candidate.containerType == CONTAINER_TYPE_MAP &&
                (candidate.expectedNicknameMatches >= 2 || candidate.members.size >= 2) -> ImageBridgeProtocol.ChatRoomSnapshotConfidence.MEDIUM

            else -> ImageBridgeProtocol.ChatRoomSnapshotConfidence.LOW
        }

    private fun collectContainers(room: Any): List<ContainerCandidate> {
        val containers = mutableListOf<ContainerCandidate>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

        fun visit(
            path: String,
            value: Any?,
            depth: Int,
        ) {
            if (value == null || depth > MAX_GRAPH_DEPTH || isSimpleValue(value)) {
                return
            }
            if (!visited.add(value)) {
                return
            }
            when {
                value is Collection<*> -> {
                    val elements = value.filterNotNull().take(MAX_CONTAINER_ELEMENTS)
                    if (elements.isNotEmpty()) {
                        containers += ContainerCandidate.CollectionContainer(path = path, elements = elements)
                    }
                    elements.forEachIndexed { index, element ->
                        visit("$path[$index]", element, depth + 1)
                    }
                }

                value is Map<*, *> -> {
                    val entries = value.entries.filter { it.key != null || it.value != null }.take(MAX_CONTAINER_ELEMENTS)
                    if (entries.isNotEmpty()) {
                        containers += ContainerCandidate.MapContainer(path = path, entries = entries)
                    }
                    entries.forEachIndexed { index, entry ->
                        visit("$path{$index}", entry.value, depth + 1)
                    }
                }

                value.javaClass.isArray -> {
                    val size = minOf(ReflectArray.getLength(value), MAX_CONTAINER_ELEMENTS)
                    val elements =
                        buildList(size) {
                            repeat(size) { index ->
                                ReflectArray.get(value, index)?.let(::add)
                            }
                        }
                    if (elements.isNotEmpty()) {
                        containers += ContainerCandidate.CollectionContainer(path = path, elements = elements)
                    }
                    elements.forEachIndexed { index, element ->
                        visit("$path[$index]", element, depth + 1)
                    }
                }

                shouldDescendInto(value.javaClass) -> {
                    instanceFields(value.javaClass).forEach { field ->
                        runCatching {
                            visit("$path.${field.name}", field.get(value), depth + 1)
                        }
                    }
                }
            }
        }

        visit("$", room, 0)
        return containers
    }

    private fun evaluateContainer(
        container: ContainerCandidate,
        expectedMemberIds: Set<Long>,
        expectedNicknames: Map<Long, String>,
    ): RankedContainerCandidate? {
        val views = container.views().filter { it.values.isNotEmpty() }
        if (views.isEmpty()) {
            return null
        }
        val userPath = selectUserIdPath(views, expectedMemberIds) ?: return null
        val nicknamePath = selectNicknamePath(views, userPath.path, expectedNicknames) ?: return null
        val profilePath = selectProfileImagePath(views)
        val rolePath = selectRolePath(views)
        val members =
            buildMembers(
                views = views,
                userPath = userPath.path,
                nicknamePath = nicknamePath.path,
                rolePath = rolePath?.path,
                profilePath = profilePath?.path,
            ).filter { member -> member.userId in expectedMemberIds }
        if (members.isEmpty()) {
            return null
        }

        val matchedExpected =
            expectedMemberIds.count { expectedId ->
                members.any { member -> member.userId == expectedId }
            }
        val expectedNicknameMatches =
            members.count { member ->
                expectedNicknames[member.userId] == member.nickname
            }
        val classBonus =
            views
                .groupingBy { it.className }
                .eachCount()
                .values
                .maxOrNull()
                ?: 0
        val plan =
            ExtractionPlan(
                containerPath = container.path,
                sourceClassName = views.firstOrNull { it.className != "<null>" }?.className,
                userIdPath = userPath.path,
                nicknamePath = nicknamePath.path,
                rolePath = rolePath?.path,
                profileImagePath = profilePath?.path,
            )
        return RankedContainerCandidate(
            plan = plan,
            members = members,
            score =
                members.size * 1_000 +
                    matchedExpected * 400 +
                    userPath.score +
                    nicknamePath.score +
                    (rolePath?.score ?: 0) +
                    (profilePath?.score ?: 0) +
                    containerPathScore(container.path) +
                    container.typeScore() +
                    classBonus * 20,
            expectedNicknameMatches = expectedNicknameMatches,
            matchedExpectedCount = matchedExpected,
            hasRolePath = rolePath != null,
            hasProfilePath = profilePath != null,
            sourceClassName = plan.sourceClassName,
            containerType = container.typeLabel(),
            genericLabelPenalty = nicknamePath.genericPenalty,
        )
    }

    private fun buildMembers(
        views: List<ElementView>,
        userPath: String,
        nicknamePath: String,
        rolePath: String?,
        profilePath: String?,
    ): List<ImageBridgeProtocol.ChatRoomMemberSnapshot> {
        val deduped = linkedMapOf<Long, ImageBridgeProtocol.ChatRoomMemberSnapshot>()
        views.forEach { view ->
            val userId = primitiveLongValue(view.values[userPath])?.takeIf { it > 0L } ?: return@forEach
            val nickname =
                (view.values[nicknamePath] as? PrimitiveValue.StringValue)
                    ?.value
                    ?.trim()
                    ?.takeIf(::looksLikeNickname)
                    ?: return@forEach
            val candidate =
                ImageBridgeProtocol.ChatRoomMemberSnapshot(
                    userId = userId,
                    nickname = nickname,
                    roleCode = rolePath?.let { path -> parseRoleCode(view.values[path]) },
                    profileImageUrl =
                        profilePath
                            ?.let { path -> (view.values[path] as? PrimitiveValue.StringValue)?.value?.trim() }
                            ?.takeIf(::looksLikeProfileUrl),
                )
            val current = deduped[userId]
            if (current == null || completeness(candidate) > completeness(current)) {
                deduped[userId] = candidate
            }
        }
        return deduped.values.toList()
    }

    private fun selectUserIdPath(
        views: List<ElementView>,
        expectedMemberIds: Set<Long>,
    ): ScoredPath? =
        views
            .flatMap { view ->
                view.values.mapNotNull { (path, value) ->
                    val longValue = primitiveLongValue(value)?.takeIf { it > 0L } ?: return@mapNotNull null
                    path to longValue
                }
            }.groupBy({ it.first }, { it.second })
            .mapNotNull { (path, values) ->
                val distinctValues = values.distinct()
                val expectedHits = distinctValues.count { it in expectedMemberIds }
                if (values.size > 1 && distinctValues.size <= 1 && expectedHits == 0) {
                    return@mapNotNull null
                }
                val largeValueBonus = values.count { it > Int.MAX_VALUE }
                ScoredPath(
                    path = path,
                    score =
                        expectedHits * 1_000 +
                            distinctValues.size * 80 +
                            values.size * 12 +
                            largeValueBonus * 5 +
                            pathHintScore(
                                path = path,
                                preferredTokens = setOf("user", "member", "id", "uid"),
                                discouragedTokens = setOf("chat", "room", "link"),
                            ),
                )
            }.maxByOrNull { scored -> scored.score }

    private fun selectNicknamePath(
        views: List<ElementView>,
        userPath: String,
        expectedNicknames: Map<Long, String>,
    ): ScoredPath? =
        views
            .flatMap { view ->
                val userId = (view.values[userPath] as? PrimitiveValue.LongValue)?.value
                view.values.mapNotNull { (path, value) ->
                    val stringValue = (value as? PrimitiveValue.StringValue)?.value?.trim() ?: return@mapNotNull null
                    if (!looksLikeNickname(stringValue)) {
                        return@mapNotNull null
                    }
                    CandidateStringValue(path = path, value = stringValue, userId = userId)
                }
            }.groupBy(CandidateStringValue::path)
            .mapNotNull { (path, values) ->
                if (values.isEmpty()) {
                    return@mapNotNull null
                }
                val distinctValues = values.map(CandidateStringValue::value).distinct()
                val expectedMatches = values.count { candidate -> candidate.userId?.let(expectedNicknames::get) == candidate.value }
                val labelPenalty = values.sumOf { candidate -> genericLabelPenalty(candidate.value) }
                val humanLikeBonus = values.sumOf { candidate -> nicknameQualityScore(candidate.value) }
                ScoredPath(
                    path = path,
                    score =
                        values.size * 40 +
                            distinctValues.size * 12 +
                            expectedMatches * 500 +
                            humanLikeBonus -
                            labelPenalty +
                            pathHintScore(
                                path = path,
                                preferredTokens = setOf("nickname", "nick", "name", "display"),
                                discouragedTokens = setOf("url", "image", "path", "id", "value", "label"),
                            ),
                    genericPenalty = labelPenalty,
                )
            }.maxByOrNull { scored -> scored.score }

    private fun selectProfileImagePath(views: List<ElementView>): ScoredPath? =
        views
            .flatMap { view ->
                view.values.mapNotNull { (path, value) ->
                    val stringValue = (value as? PrimitiveValue.StringValue)?.value?.trim() ?: return@mapNotNull null
                    if (!looksLikeProfileUrl(stringValue)) {
                        return@mapNotNull null
                    }
                    path to stringValue
                }
            }.groupBy({ it.first }, { it.second })
            .map { (path, values) ->
                ScoredPath(
                    path = path,
                    score =
                        values.size * 20 +
                            pathHintScore(
                                path = path,
                                preferredTokens = setOf("profile", "image", "avatar", "url"),
                                discouragedTokens = emptySet(),
                            ),
                )
            }.maxByOrNull { scored -> scored.score }

    private fun selectRolePath(views: List<ElementView>): ScoredPath? =
        views
            .flatMap { view ->
                view.values.mapNotNull { (path, value) -> parseRoleCode(value)?.let { path to it } }
            }.groupBy({ it.first }, { it.second })
            .map { (path, values) ->
                ScoredPath(
                    path = path,
                    score =
                        values.size * 20 +
                            values.distinct().size * 10 +
                            pathHintScore(
                                path = path,
                                preferredTokens = setOf("role", "type", "member"),
                                discouragedTokens = emptySet(),
                            ),
                )
            }.maxByOrNull { scored -> scored.score }

    private fun parseRoleCode(value: PrimitiveValue?): Int? =
        when (value) {
            is PrimitiveValue.LongValue -> value.value.toInt().takeIf { it in KNOWN_ROLE_CODES }
            is PrimitiveValue.StringValue ->
                when (value.value.trim().lowercase()) {
                    "owner", "master", "host" -> 1
                    "admin", "manager" -> 4
                    "bot" -> 8
                    "member", "normal" -> 2
                    else -> null
                }

            null -> null
        }

    private fun completeness(
        member: ImageBridgeProtocol.ChatRoomMemberSnapshot,
    ): Int = listOfNotNull(member.roleCode, member.profileImageUrl?.takeIf(String::isNotBlank)).size

    private fun looksLikeNickname(value: String): Boolean {
        if (value.isBlank() || value.length > MAX_NICKNAME_LENGTH) {
            return false
        }
        if (looksLikeProfileUrl(value)) {
            return false
        }
        if (looksLikeInternalArtifactValue(value)) {
            return false
        }
        return true
    }

    private fun nicknameQualityScore(value: String): Int {
        val normalized = value.trim()
        var score = 0
        if (normalized.any { char -> char.code > 0x7f }) score += 30
        if (normalized.any { char -> char == ' ' || char == '[' || char == ']' }) score += 12
        return score
    }

    private fun genericLabelPenalty(value: String): Int {
        val normalized = value.trim().lowercase()
        return if (normalized in GENERIC_LABEL_VALUES) 220 else 0
    }

    private fun looksLikeInternalArtifactValue(value: String): Boolean {
        val normalized = value.trim()
        if (normalized.length < 16) {
            return false
        }
        val lowercase = normalized.lowercase()
        val hasArtifactToken = INTERNAL_ARTIFACT_TOKENS.any { token -> lowercase.contains(token) }
        val asciiIdentifierLike = normalized.all { it.isLetterOrDigit() || it == '_' || it == '-' }
        return asciiIdentifierLike && hasArtifactToken
    }

    private fun looksLikeProfileUrl(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return normalized.startsWith("http://") ||
            normalized.startsWith("https://") ||
            normalized.startsWith("content://") ||
            normalized.startsWith("file://") ||
            normalized.contains("/profile") ||
            normalized.contains(".png") ||
            normalized.contains(".jpg") ||
            normalized.contains(".jpeg") ||
            normalized.contains(".webp")
    }

    private fun primitiveLongValue(value: PrimitiveValue?): Long? =
        when (value) {
            is PrimitiveValue.LongValue -> value.value
            is PrimitiveValue.StringValue -> value.value.trim().toLongOrNull()
            null -> null
        }

    private fun logMissingCandidateDiagnostics(
        roomId: Long,
        containers: List<ContainerCandidate>,
        expectedMemberIds: Set<Long>,
        expectedNicknames: Map<Long, String>,
    ) {
        val summary =
            containers
                .take(MAX_DEBUG_CONTAINER_SUMMARY)
                .joinToString(separator = " || ") { container -> container.debugSummary() }
        Log.w(
            TAG,
            "missing member candidates roomId=$roomId expectedMemberIds=${expectedMemberIds.take(MAX_DEBUG_MEMBER_IDS)} expectedNicknames=${expectedNicknames.values.take(MAX_DEBUG_NICKNAMES)} containers=${containers.size} summary=$summary",
        )
    }

    private fun containerPathScore(path: String): Int =
        pathHintScore(
            path = path,
            preferredTokens = setOf("member", "members", "user", "users", "participant", "participants"),
            discouragedTokens = setOf("notice", "message", "thread", "backup"),
        )

    private fun pathHintScore(
        path: String,
        preferredTokens: Set<String>,
        discouragedTokens: Set<String>,
    ): Int {
        val normalized = path.lowercase()
        val preferred = preferredTokens.count { token -> normalized.contains(token) }
        val discouraged = discouragedTokens.count { token -> normalized.contains(token) }
        return preferred * 25 - discouraged * 15
    }

    private fun ContainerCandidate.views(): List<ElementView> =
        when (this) {
            is ContainerCandidate.CollectionContainer ->
                elements.map { element ->
                    ElementView(
                        className = element.javaClass.name,
                        values = flattenElement(element),
                    )
                }

            is ContainerCandidate.MapContainer ->
                entries.map { entry ->
                    ElementView(
                        className = entry.value?.javaClass?.name ?: entry.key?.javaClass?.name ?: "<null>",
                        values = flattenMapEntry(entry),
                    )
                }
        }

    private fun ContainerCandidate.typeScore(): Int =
        when (this) {
            is ContainerCandidate.CollectionContainer -> 120
            is ContainerCandidate.MapContainer -> -120
        }

    private fun ContainerCandidate.typeLabel(): String =
        when (this) {
            is ContainerCandidate.CollectionContainer -> CONTAINER_TYPE_COLLECTION
            is ContainerCandidate.MapContainer -> CONTAINER_TYPE_MAP
        }

    private fun ContainerCandidate.debugSummary(): String {
        val views = views().take(MAX_DEBUG_VIEW_COUNT)
        val classes = views.map(ElementView::className).distinct().take(MAX_DEBUG_PATHS)
        val paths = views.flatMap { view -> view.values.keys }.distinct().take(MAX_DEBUG_PATHS)
        val samples =
            views
                .flatMap { view ->
                    view.values.mapNotNull { (path, value) ->
                        when (value) {
                            is PrimitiveValue.StringValue -> "$path=${value.value.take(MAX_DEBUG_VALUE_LENGTH)}"
                            is PrimitiveValue.LongValue -> "$path=${value.value}"
                        }
                    }
                }.distinct()
                .take(MAX_DEBUG_SAMPLE_COUNT)
        return "path=$path type=${typeLabel()} classes=$classes paths=$paths samples=$samples"
    }

    private fun flattenMapEntry(entry: Map.Entry<*, *>): Map<String, PrimitiveValue> =
        linkedMapOf<String, PrimitiveValue>().apply {
            addSimpleValue(this, path = "key", value = entry.key)
            val value = entry.value
            if (value == null || isSimpleValue(value)) {
                addSimpleValue(this, path = "value", value = value)
            } else {
                flattenObject(
                    value = value,
                    prefix = "value",
                    depth = 0,
                    output = this,
                    visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()),
                )
            }
        }

    private fun flattenElement(element: Any): Map<String, PrimitiveValue> =
        linkedMapOf<String, PrimitiveValue>().apply {
            flattenObject(
                value = element,
                prefix = null,
                depth = 0,
                output = this,
                visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()),
            )
        }

    private fun flattenObject(
        value: Any?,
        prefix: String?,
        depth: Int,
        output: MutableMap<String, PrimitiveValue>,
        visited: MutableSet<Any>,
    ) {
        if (value == null || depth > MAX_MEMBER_FIELD_DEPTH) {
            return
        }
        if (isSimpleValue(value)) {
            addSimpleValue(output, prefix ?: "value", value)
            return
        }
        if (!shouldDescendInto(value.javaClass) || !visited.add(value)) {
            return
        }
        instanceFields(value.javaClass).forEach { field ->
            runCatching {
                val child = field.get(value)
                val childPath = prefix?.let { "$it.${field.name}" } ?: field.name
                if (child == null || isSimpleValue(child)) {
                    addSimpleValue(output, childPath, child)
                } else if (!child.javaClass.isArray && child !is Collection<*> && child !is Map<*, *>) {
                    flattenObject(child, childPath, depth + 1, output, visited)
                }
            }
        }
    }

    private fun addSimpleValue(
        output: MutableMap<String, PrimitiveValue>,
        path: String,
        value: Any?,
    ) {
        val primitive =
            when (value) {
                is Byte -> PrimitiveValue.LongValue(value.toLong())
                is Short -> PrimitiveValue.LongValue(value.toLong())
                is Int -> PrimitiveValue.LongValue(value.toLong())
                is Long -> PrimitiveValue.LongValue(value)
                is String -> PrimitiveValue.StringValue(value)
                is Enum<*> -> PrimitiveValue.StringValue(value.name)
                else -> null
            } ?: return
        output[path] = primitive
    }

    private fun instanceFields(clazz: Class<*>): List<Field> =
        fieldCache.computeIfAbsent(clazz) { resolvedClass ->
            buildList {
                var current: Class<*>? = resolvedClass
                while (current != null && current != Any::class.java) {
                    current.declaredFields
                        .filterNot { field -> Modifier.isStatic(field.modifiers) }
                        .forEach { field ->
                            field.isAccessible = true
                            add(field)
                        }
                    current = current.superclass
                }
            }
        }

    private fun shouldDescendInto(clazz: Class<*>): Boolean {
        val name = clazz.name
        return !clazz.isPrimitive &&
            !name.startsWith("java.") &&
            !name.startsWith("javax.") &&
            !name.startsWith("kotlin.") &&
            !name.startsWith("android.")
    }

    private fun isSimpleValue(value: Any): Boolean =
        value is String ||
            value is Byte ||
            value is Short ||
            value is Int ||
            value is Long ||
            value is Enum<*>

    private fun toInternalPlan(plan: ImageBridgeProtocol.ChatRoomMemberExtractionPlan): ExtractionPlan =
        ExtractionPlan(
            containerPath = plan.containerPath,
            sourceClassName = plan.sourceClassName,
            userIdPath = plan.userIdPath,
            nicknamePath = plan.nicknamePath,
            rolePath = plan.rolePath,
            profileImagePath = plan.profileImagePath,
        )

    private fun ExtractionPlan.toProtocolPlan(): ImageBridgeProtocol.ChatRoomMemberExtractionPlan =
        ImageBridgeProtocol.ChatRoomMemberExtractionPlan(
            containerPath = containerPath,
            sourceClassName = sourceClassName,
            userIdPath = userIdPath,
            nicknamePath = nicknamePath,
            rolePath = rolePath,
            profileImagePath = profileImagePath,
            fingerprint = fingerprint(),
        )

    private sealed interface ContainerCandidate {
        val path: String

        data class CollectionContainer(
            override val path: String,
            val elements: List<Any>,
        ) : ContainerCandidate

        data class MapContainer(
            override val path: String,
            val entries: List<Map.Entry<*, *>>,
        ) : ContainerCandidate
    }

    private sealed interface PrimitiveValue {
        data class LongValue(
            val value: Long,
        ) : PrimitiveValue

        data class StringValue(
            val value: String,
        ) : PrimitiveValue
    }

    private data class ExtractionPlan(
        val containerPath: String,
        val sourceClassName: String?,
        val userIdPath: String,
        val nicknamePath: String,
        val rolePath: String? = null,
        val profileImagePath: String? = null,
    ) {
        fun fingerprint(): String =
            listOfNotNull(
                containerPath,
                sourceClassName,
                userIdPath,
                nicknamePath,
                rolePath,
                profileImagePath,
            ).joinToString("|")
    }

    private data class ElementView(
        val className: String,
        val values: Map<String, PrimitiveValue>,
    )

    private data class CandidateStringValue(
        val path: String,
        val value: String,
        val userId: Long?,
    )

    private data class ScoredPath(
        val path: String,
        val score: Int,
        val genericPenalty: Int = 0,
    )

    private data class RankedContainerCandidate(
        val plan: ExtractionPlan,
        val members: List<ImageBridgeProtocol.ChatRoomMemberSnapshot>,
        val score: Int,
        val expectedNicknameMatches: Int,
        val matchedExpectedCount: Int,
        val hasRolePath: Boolean,
        val hasProfilePath: Boolean,
        val sourceClassName: String?,
        val containerType: String,
        val genericLabelPenalty: Int,
    )

    private companion object {
        const val MAX_GRAPH_DEPTH = 4
        const val MAX_CONTAINER_ELEMENTS = 80
        const val MAX_MEMBER_FIELD_DEPTH = 4
        const val MAX_NICKNAME_LENGTH = 80
        const val CONTAINER_TYPE_COLLECTION = "collection"
        const val CONTAINER_TYPE_MAP = "map"
        const val MAX_DEBUG_CONTAINER_SUMMARY = 8
        const val MAX_DEBUG_VIEW_COUNT = 2
        const val MAX_DEBUG_PATHS = 10
        const val MAX_DEBUG_SAMPLE_COUNT = 12
        const val MAX_DEBUG_VALUE_LENGTH = 48
        const val MAX_DEBUG_MEMBER_IDS = 8
        const val MAX_DEBUG_NICKNAMES = 8
        const val TAG = "IrisBridge"
        val KNOWN_ROLE_CODES = setOf(0, 1, 2, 4, 8)
        val GENERIC_LABEL_VALUES = setOf("notice", "system", "default", "unknown", "profile", "member")
        val INTERNAL_ARTIFACT_TOKENS =
            setOf(
                "backup",
                "openlink",
                "chatmember",
                "memberid",
                "userid",
                "nickname",
                "profile",
            )
    }
}
