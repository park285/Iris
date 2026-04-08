@file:Suppress("ProguardSerializableOutsideModel")

package party.qwer.iris

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import party.qwer.iris.model.NicknameChangeEvent
import party.qwer.iris.persistence.InMemoryLiveRoomMemberPlanStore
import party.qwer.iris.persistence.LiveRoomMemberPlanStore
import party.qwer.iris.persistence.MemberIdentityStateStore
import party.qwer.iris.persistence.RoomEventStore
import party.qwer.iris.persistence.StoredLiveRoomMemberIdentity
import party.qwer.iris.persistence.StoredLiveRoomMemberPlan
import party.qwer.iris.snapshot.RoomSnapshotReadResult
import party.qwer.iris.snapshot.RoomSnapshotReader
import party.qwer.iris.snapshot.SnapshotEventEmitter
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.UserId

@Serializable
internal data class MemberNicknameDiagnostics(
    val chatId: Long,
    val confirmedNicknames: Map<Long, String> = emptyMap(),
    val pendingNicknames: Map<Long, PendingNicknameDiagnostics> = emptyMap(),
    val preferredPlan: LiveRoomMemberExtractionPlan? = null,
    val lastLiveSnapshot: LiveSnapshotDiagnostics? = null,
)

@Serializable
internal data class PendingNicknameDiagnostics(
    val nickname: String,
    val source: NicknameEvidenceSource,
    val confidence: NicknameEvidenceConfidence,
    val sameEvidenceCount: Int,
    val corroboratedByOtherSource: Boolean,
    val planFingerprint: String? = null,
)

@Serializable
internal data class LiveSnapshotDiagnostics(
    val sourcePath: String? = null,
    val sourceClassName: String? = null,
    val confidence: LiveSnapshotConfidence = LiveSnapshotConfidence.LOW,
    val confidenceScore: Int = 0,
    val usedPreferredPlan: Boolean = false,
    val candidateGap: Int? = null,
    val selectedPlan: LiveRoomMemberExtractionPlan? = null,
)

internal class MemberIdentityObserver(
    private val roomSnapshotReader: RoomSnapshotReader,
    private val emitter: SnapshotEventEmitter,
    private val stateStore: MemberIdentityStateStore,
    private val liveRoomMemberPlanStore: LiveRoomMemberPlanStore = InMemoryLiveRoomMemberPlanStore(),
    private val roomEventStore: RoomEventStore? = null,
    private val liveMemberSnapshotProvider: LiveRoomMemberSnapshotProvider? = null,
    private val intervalMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val confirmedNicknames = linkedMapOf<ChatId, MutableMap<UserId, String>>()
    private val nicknameStates = linkedMapOf<ChatId, MutableMap<UserId, MemberNicknameRuntimeState>>()
    private val preferredPlans = linkedMapOf<ChatId, StoredLiveRoomMemberPlan>()
    private val lastLiveSnapshots = linkedMapOf<ChatId, LiveRoomMemberSnapshot>()
    private val lastAlertedNicknames = linkedMapOf<ChatId, MutableMap<UserId, String>>()
    private val lastLoadedEventIdByChat = linkedMapOf<ChatId, Long>()
    private val lowConfidencePlanStreak = linkedMapOf<ChatId, LowConfidencePlanTracker>()
    private val decisionEngine = MemberNicknameDecisionEngine()
    private val serverJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Volatile
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) {
            return
        }

        confirmedNicknames.clear()
        nicknameStates.clear()
        preferredPlans.clear()
        lastLiveSnapshots.clear()
        lastAlertedNicknames.clear()
        lastLoadedEventIdByChat.clear()
        lowConfidencePlanStreak.clear()

        confirmedNicknames.putAll(
            stateStore.loadAll().mapValues { (_, nicknames) -> sanitizeObservedNicknames(nicknames).toMutableMap() },
        )
        preferredPlans.putAll(liveRoomMemberPlanStore.loadAll())
        hydrateDecisionStatesFromConfirmedNicknames()
        primeMissingRooms()

        job =
            coroutineScope.launch {
                while (isActive) {
                    try {
                        pollAllRooms()
                    } catch (error: Exception) {
                        IrisLogger.error("[MemberIdentityObserver] error: ${error.message}", error)
                    }
                    delay(intervalMs.takeIf { it > 0L } ?: 100L)
                }
            }

        IrisLogger.info("[MemberIdentityObserver] started (intervalMs=$intervalMs)")
    }

    fun stop() {
        runBlocking { stopSuspend() }
    }

    suspend fun stopSuspend() {
        val captured =
            synchronized(this) {
                val current = job ?: return
                job = null
                current
            }
        captured.cancelAndJoin()
        IrisLogger.info("[MemberIdentityObserver] stopped")
    }

    fun diagnostics(chatId: Long): MemberNicknameDiagnostics? {
        val resolvedChatId = ChatId(chatId)
        val confirmed = confirmedNicknames[resolvedChatId].orEmpty()
        val states = nicknameStates[resolvedChatId].orEmpty()
        val preferredPlan = preferredPlans[resolvedChatId]?.plan
        val liveSnapshot = lastLiveSnapshots[resolvedChatId]
        if (confirmed.isEmpty() && states.isEmpty() && preferredPlan == null && liveSnapshot == null) {
            return null
        }
        return MemberNicknameDiagnostics(
            chatId = chatId,
            confirmedNicknames = confirmed.mapKeys { (userId, _) -> userId.value },
            pendingNicknames =
                states
                    .mapNotNull { (userId, state) ->
                        state.pending?.let { pending ->
                            userId.value to
                                PendingNicknameDiagnostics(
                                    nickname = pending.nickname,
                                    source = pending.source,
                                    confidence = pending.confidence,
                                    sameEvidenceCount = pending.sameEvidenceCount,
                                    corroboratedByOtherSource = pending.corroboratedByOtherSource,
                                    planFingerprint = pending.planFingerprint,
                                )
                        }
                    }.toMap(linkedMapOf()),
            preferredPlan = preferredPlan,
            lastLiveSnapshot = liveSnapshot?.toDiagnostics(),
        )
    }

    private fun hydrateDecisionStatesFromConfirmedNicknames() {
        confirmedNicknames.forEach { (chatId, nicknames) ->
            val chatStates = nicknameStates.getOrPut(chatId) { linkedMapOf() }
            nicknames.forEach { (userId, nickname) ->
                chatStates[userId] =
                    MemberNicknameRuntimeState(confirmedNickname = nickname).apply {
                        recentConfirmedHistory.addLast(
                            ConfirmedNicknameEntry(
                                nickname = nickname,
                                confirmedAtMs = clock(),
                            ),
                        )
                    }
            }
        }
    }

    private fun primeMissingRooms() {
        val roomIds = roomSnapshotReader.listRoomChatIds().filter { it.value > 0L }.distinct()
        if (roomIds.isEmpty()) {
            return
        }

        roomIds.forEach { roomId ->
            if (confirmedNicknames.containsKey(roomId)) {
                return@forEach
            }

            runCatching {
                when (val result = roomSnapshotReader.snapshot(roomId)) {
                    is RoomSnapshotReadResult.Present -> {
                        val currentNicknames = normalizedNicknames(result.snapshot.nicknames).toMutableMap()
                        confirmedNicknames[roomId] = currentNicknames
                        if (currentNicknames.isNotEmpty()) {
                            val chatStates = nicknameStates.getOrPut(roomId) { linkedMapOf() }
                            currentNicknames.forEach { (userId, nickname) ->
                                chatStates[userId] =
                                    MemberNicknameRuntimeState(confirmedNickname = nickname).apply {
                                        recentConfirmedHistory.addLast(
                                            ConfirmedNicknameEntry(
                                                nickname = nickname,
                                                confirmedAtMs = clock(),
                                            ),
                                        )
                                    }
                            }
                            stateStore.save(roomId, currentNicknames)
                        }
                    }

                    RoomSnapshotReadResult.Missing -> Unit
                }
            }.onFailure { error ->
                IrisLogger.error(
                    "[MemberIdentityObserver] prime failed chatId=${roomId.value}: ${error.message}",
                    error,
                )
            }
        }
    }

    private fun pollAllRooms() {
        val roomIds = roomSnapshotReader.listRoomChatIds().filter { it.value > 0L }.distinct()
        if (roomIds.isEmpty()) {
            return
        }

        roomIds.forEach { roomId ->
            runCatching {
                when (val result = roomSnapshotReader.snapshot(roomId)) {
                    is RoomSnapshotReadResult.Present -> handlePresentSnapshot(effectiveSnapshot(result.snapshot))
                    RoomSnapshotReadResult.Missing -> Unit
                }
            }.onFailure { error ->
                IrisLogger.error(
                    "[MemberIdentityObserver] room poll failed chatId=${roomId.value}: ${error.message}",
                    error,
                )
            }
        }
    }

    private fun effectiveSnapshot(snapshot: RoomSnapshotData): ResolvedRoomSnapshot {
        val liveSnapshot = resolveLiveMemberSnapshot(snapshot) ?: return ResolvedRoomSnapshot(snapshot = snapshot)
        lastLiveSnapshots[snapshot.chatId] = liveSnapshot
        return ResolvedRoomSnapshot(snapshot = snapshot, liveSnapshot = liveSnapshot)
    }

    private fun resolveLiveMemberSnapshot(snapshot: RoomSnapshotData): LiveRoomMemberSnapshot? {
        val provider = liveMemberSnapshotProvider ?: return null
        val expectedMembers = expectedMembers(snapshot)
        if (expectedMembers.isEmpty()) {
            return null
        }
        val preferredPlan = preferredPlans[snapshot.chatId]?.plan
        return runCatching {
            provider.snapshot(
                chatId = snapshot.chatId,
                expectedMembers = expectedMembers,
                preferredPlan = preferredPlan,
            )
        }.onFailure { error ->
            IrisLogger.error(
                "[MemberIdentityObserver] live snapshot failed chatId=${snapshot.chatId.value}: ${error.message}",
                error,
            )
        }.getOrNull()
            ?.takeIf { it.members.isNotEmpty() }
    }

    private fun expectedMembers(snapshot: RoomSnapshotData): List<LiveRoomMemberHint> {
        val storedMembersByUserId =
            preferredPlans[snapshot.chatId]
                ?.lastKnownMembers
                ?.associateBy { identity -> UserId(identity.userId) }
                .orEmpty()

        val expectedUserIds =
            linkedSetOf<UserId>().apply {
                addAll(snapshot.memberIds)
                addAll(snapshot.nicknames.keys)
                addAll(confirmedNicknames[snapshot.chatId].orEmpty().keys)
                addAll(storedMembersByUserId.keys)
            }

        return expectedUserIds.map { userId ->
            LiveRoomMemberHint(
                userId = userId,
                nickname =
                    normalizeObservedNickname(confirmedNicknames[snapshot.chatId]?.get(userId))
                        ?: normalizeObservedNickname(snapshot.nicknames[userId])
                        ?: normalizeObservedNickname(storedMembersByUserId[userId]?.nickname),
            )
        }
    }

    private fun handlePresentSnapshot(resolvedSnapshot: ResolvedRoomSnapshot) {
        val snapshot = resolvedSnapshot.snapshot
        val chatStates = nicknameStates.getOrPut(snapshot.chatId) { linkedMapOf() }
        val chatConfirmed = confirmedNicknames.getOrPut(snapshot.chatId) { linkedMapOf() }
        val events = mutableListOf<NicknameChangeEvent>()

        val observedUserIds =
            linkedSetOf<UserId>().apply {
                addAll(snapshot.memberIds)
                addAll(snapshot.nicknames.keys)
                addAll(chatConfirmed.keys)
                addAll(
                    resolvedSnapshot.liveSnapshot
                        ?.members
                        ?.keys
                        .orEmpty(),
                )
                addAll(
                    preferredPlans[snapshot.chatId]
                        ?.lastKnownMembers
                        ?.map { identity -> UserId(identity.userId) }
                        .orEmpty(),
                )
            }

        observedUserIds.forEach { userId ->
            val state =
                chatStates.getOrPut(userId) {
                    MemberNicknameRuntimeState(
                        confirmedNickname = chatConfirmed[userId],
                    ).also { runtimeState ->
                        chatConfirmed[userId]?.let { nickname ->
                            runtimeState.recentConfirmedHistory.addLast(
                                ConfirmedNicknameEntry(
                                    nickname = nickname,
                                    confirmedAtMs = clock(),
                                ),
                            )
                        }
                    }
                }

            val observation = observationFor(snapshot, resolvedSnapshot.liveSnapshot, userId)
            when (val decision = decisionEngine.apply(state, observation)) {
                NicknameDecision.NoChange -> {
                    state.confirmedNickname?.let { confirmed ->
                        chatConfirmed[userId] = confirmed
                        missedNicknameEvent(snapshot, userId, confirmed, observation)?.let(events::add)
                    }
                }

                is NicknameDecision.Seed -> {
                    chatConfirmed[userId] = decision.nickname
                }

                is NicknameDecision.Confirm -> {
                    val latestAlerted = latestAlertedNickname(snapshot.chatId, userId)
                    if (latestAlerted == decision.newNickname) {
                        chatConfirmed[userId] = decision.newNickname
                    } else {
                        events +=
                            NicknameChangeEvent(
                                chatId = snapshot.chatId.value,
                                linkId = snapshot.linkId?.value,
                                userId = userId.value,
                                oldNickname = decision.oldNickname,
                                newNickname = decision.newNickname,
                                timestamp = clock() / 1000,
                            )
                        chatConfirmed[userId] = decision.newNickname
                    }
                }
            }
        }

        if (events.isNotEmpty()) {
            emitter.emit(events)
            events.forEach { event ->
                event.newNickname?.let { nickname ->
                    rememberAlertedNickname(snapshot.chatId, UserId(event.userId), nickname)
                }
            }
        }

        rememberPreferredPlan(snapshot.chatId, resolvedSnapshot.liveSnapshot)
        stateStore.save(snapshot.chatId, chatConfirmed)
    }

    private fun missedNicknameEvent(
        snapshot: RoomSnapshotData,
        userId: UserId,
        confirmedNickname: String,
        observation: NicknameObservation?,
    ): NicknameChangeEvent? {
        if (observation?.nickname != confirmedNickname) {
            return null
        }
        val latestAlerted = latestAlertedNickname(snapshot.chatId, userId) ?: return null
        if (latestAlerted == confirmedNickname) {
            return null
        }
        return NicknameChangeEvent(
            chatId = snapshot.chatId.value,
            linkId = snapshot.linkId?.value,
            userId = userId.value,
            oldNickname = latestAlerted,
            newNickname = confirmedNickname,
            timestamp = clock() / 1000,
        )
    }

    private fun observationFor(
        snapshot: RoomSnapshotData,
        liveSnapshot: LiveRoomMemberSnapshot?,
        userId: UserId,
    ): NicknameObservation? {
        val now = clock()
        liveSnapshot?.members?.get(userId)?.let { member ->
            val nickname = normalizeObservedNickname(member.nickname) ?: return@let null
            return NicknameObservation(
                nickname = nickname,
                source = NicknameEvidenceSource.LIVE,
                confidence = liveSnapshot.confidence.toEvidenceConfidence(),
                observedAtMs = now,
                planFingerprint = liveSnapshot.selectedPlan?.fingerprint,
            )
        }

        val dbNickname = normalizeObservedNickname(snapshot.nicknames[userId]) ?: return null
        return NicknameObservation(
            nickname = dbNickname,
            source = NicknameEvidenceSource.DB,
            confidence = NicknameEvidenceConfidence.MEDIUM,
            observedAtMs = now,
            planFingerprint = null,
        )
    }

    private fun rememberPreferredPlan(
        chatId: ChatId,
        liveSnapshot: LiveRoomMemberSnapshot?,
    ) {
        val snapshot = liveSnapshot ?: return
        val plan = snapshot.selectedPlan ?: return
        if (snapshot.members.isEmpty()) {
            return
        }

        if (snapshot.confidence == LiveSnapshotConfidence.LOW) {
            if (shouldPersistCorroboratedLowConfidencePlan(chatId, snapshot)) {
                savePlan(chatId, snapshot)
                lowConfidencePlanStreak.remove(chatId)
                return
            }

            // LOW confidence 플랜이 동일 fingerprint로 반복 선택되면 preferred로 승격
            promoteIfConsistentLowConfidence(chatId, snapshot)
            return
        }

        savePlan(chatId, snapshot)
    }

    private fun shouldPersistCorroboratedLowConfidencePlan(
        chatId: ChatId,
        snapshot: LiveRoomMemberSnapshot,
    ): Boolean {
        val confirmed = confirmedNicknames[chatId].orEmpty()
        if (confirmed.isEmpty()) {
            return false
        }

        val observedMembers = snapshot.members.values
        if (observedMembers.isEmpty()) {
            return false
        }

        return observedMembers.all { member ->
            normalizeObservedNickname(confirmed[member.userId]) == normalizeObservedNickname(member.nickname)
        }
    }

    private fun promoteIfConsistentLowConfidence(
        chatId: ChatId,
        snapshot: LiveRoomMemberSnapshot,
    ) {
        val fingerprint = snapshot.selectedPlan?.fingerprint ?: return
        val tracker = lowConfidencePlanStreak[chatId]
        if (tracker != null && tracker.fingerprint == fingerprint) {
            tracker.count++
            if (tracker.count >= LOW_CONFIDENCE_PLAN_PROMOTION_THRESHOLD) {
                savePlan(chatId, snapshot)
                lowConfidencePlanStreak.remove(chatId)
                IrisLogger.info(
                    "[MemberIdentityObserver] promoted LOW confidence plan chatId=${chatId.value} fingerprint=$fingerprint",
                )
            }
        } else {
            lowConfidencePlanStreak[chatId] = LowConfidencePlanTracker(fingerprint, 1)
        }
    }

    private fun savePlan(
        chatId: ChatId,
        snapshot: LiveRoomMemberSnapshot,
    ) {
        val plan = snapshot.selectedPlan ?: return
        val stored =
            StoredLiveRoomMemberPlan(
                plan = plan,
                lastKnownMembers =
                    snapshot.members.values.map { member ->
                        StoredLiveRoomMemberIdentity(
                            userId = member.userId.value,
                            nickname = normalizeObservedNickname(member.nickname),
                        )
                    },
            )
        preferredPlans[chatId] = stored
        liveRoomMemberPlanStore.save(chatId, stored)
    }

    private fun latestAlertedNickname(
        chatId: ChatId,
        userId: UserId,
    ): String? {
        refreshAlertHistory(chatId)
        return lastAlertedNicknames
            .getOrPut(chatId) { linkedMapOf() }[userId]
            ?.let(::normalizeObservedNickname)
    }

    private fun rememberAlertedNickname(
        chatId: ChatId,
        userId: UserId,
        nickname: String,
    ) {
        val normalizedNickname = normalizeObservedNickname(nickname) ?: return
        lastAlertedNicknames.getOrPut(chatId) { linkedMapOf() }[userId] = normalizedNickname
    }

    private fun refreshAlertHistory(chatId: ChatId) {
        val store = roomEventStore ?: return
        val roomHistory = lastAlertedNicknames.getOrPut(chatId) { linkedMapOf() }
        var afterId = lastLoadedEventIdByChat[chatId] ?: 0L
        while (true) {
            val records = store.listByChatId(chatId.value, limit = NICKNAME_HISTORY_SCAN_LIMIT, afterId = afterId)
            if (records.isEmpty()) {
                break
            }

            records.forEach { record ->
                if (record.eventType != "nickname_change") {
                    return@forEach
                }

                val nickname =
                    runCatching {
                        serverJson.decodeFromString(NicknameChangeEvent.serializer(), record.payload).newNickname?.trim()
                    }.getOrNull()
                normalizeObservedNickname(nickname)?.let { normalizedNickname ->
                    roomHistory[UserId(record.userId)] = normalizedNickname
                }
            }
            afterId = records.last().id
        }
        lastLoadedEventIdByChat[chatId] = afterId
    }

    private fun sanitizeObservedNicknames(nicknames: Map<UserId, String>): Map<UserId, String> =
        nicknames
            .mapNotNull { (userId, nickname) ->
                normalizeObservedNickname(nickname)?.let { userId to it }
            }.toMap(linkedMapOf())

    private fun normalizedNicknames(nicknames: Map<UserId, String>): Map<UserId, String> =
        nicknames
            .mapNotNull { (userId, nickname) ->
                normalizeObservedNickname(nickname)?.let { userId to it }
            }.toMap(linkedMapOf())

    private fun normalizeObservedNickname(nickname: String?): String? {
        val normalized = nickname?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (looksLikeInternalNicknameArtifact(normalized)) {
            return null
        }
        return normalized
    }

    private fun looksLikeInternalNicknameArtifact(value: String): Boolean {
        val normalized = value.trim()
        if (normalized.length < 16) {
            return false
        }
        val lowercase = normalized.lowercase()
        val hasArtifactToken =
            INTERNAL_NICKNAME_ARTIFACT_TOKENS.any { token ->
                lowercase.contains(token)
            }
        val asciiIdentifierLike = normalized.all { it.isLetterOrDigit() || it == '_' || it == '-' }
        return asciiIdentifierLike && hasArtifactToken
    }

    private fun LiveRoomMemberSnapshot.toDiagnostics(): LiveSnapshotDiagnostics =
        LiveSnapshotDiagnostics(
            sourcePath = sourcePath,
            sourceClassName = sourceClassName,
            confidence = confidence,
            confidenceScore = confidenceScore,
            usedPreferredPlan = usedPreferredPlan,
            candidateGap = candidateGap,
            selectedPlan = selectedPlan,
        )

    private companion object {
        const val NICKNAME_HISTORY_SCAN_LIMIT = 2_000
        const val LOW_CONFIDENCE_PLAN_PROMOTION_THRESHOLD = 3

        val INTERNAL_NICKNAME_ARTIFACT_TOKENS =
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

    private data class ResolvedRoomSnapshot(
        val snapshot: RoomSnapshotData,
        val liveSnapshot: LiveRoomMemberSnapshot? = null,
    )

    internal data class LowConfidencePlanTracker(
        val fingerprint: String,
        var count: Int,
    )
}
